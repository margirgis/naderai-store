
-- ══════════════════════════════════════════════════════════════════════
-- Migration 00048: Order Lifecycle v2
--   1. verification_status on wallet_topup_requests + payment_orders
--   2. REOPENED status on payment_orders
--   3. support_cases table
--   4. RPC: reopen_payment_order
--   5. RPC: admin_manual_confirm_order
--   6. RPC: open_support_case
--   7. RPC: get_topup_dashboard_stats (backend-computed counts)
--   8. UPDATE expire_payment_orders — keep data, set ADMIN_OFFLINE verification
-- ══════════════════════════════════════════════════════════════════════

-- ── 1. verification_status on wallet_topup_requests ──────────────────
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS verification_status TEXT NOT NULL DEFAULT 'waiting_for_verification'
    CHECK (verification_status IN (
      'admin_offline','waiting_for_verification','scanning',
      'matched','no_match','retrying','completed'
    ));

CREATE INDEX IF NOT EXISTS wtr_verification_status_idx
  ON wallet_topup_requests(verification_status);

-- ── 2. verification_status + REOPENED on payment_orders ──────────────
ALTER TABLE payment_orders
  ADD COLUMN IF NOT EXISTS verification_status TEXT NOT NULL DEFAULT 'waiting_for_verification'
    CHECK (verification_status IN (
      'admin_offline','waiting_for_verification','scanning',
      'matched','no_match','retrying','completed'
    ));

-- Allow 'reopened' in payment_orders.status (existing CHECK may block it)
ALTER TABLE payment_orders
  DROP CONSTRAINT IF EXISTS payment_orders_status_check;

ALTER TABLE payment_orders
  ADD CONSTRAINT payment_orders_status_check
    CHECK (status IN (
      'awaiting_payment','pending','scanning','confirmed',
      'failed','expired','cancelled','duplicate','reopened'
    ));

CREATE INDEX IF NOT EXISTS payment_orders_verification_status_idx
  ON payment_orders(verification_status);

-- ── 3. support_cases table ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS support_cases (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id        UUID NOT NULL REFERENCES payment_orders(id) ON DELETE CASCADE,
  topup_request_id UUID REFERENCES wallet_topup_requests(id),
  opened_by       UUID NOT NULL REFERENCES profiles(id),  -- admin
  customer_id     UUID NOT NULL REFERENCES profiles(id),
  status          TEXT NOT NULL DEFAULT 'open'
    CHECK (status IN ('open','investigating','resolved','closed')),
  reason          TEXT NOT NULL,
  admin_notes     TEXT,
  resolution      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at     TIMESTAMPTZ
);

ALTER TABLE support_cases ENABLE ROW LEVEL SECURITY;

CREATE POLICY "admin_all_support_cases" ON support_cases
  FOR ALL TO authenticated
  USING (EXISTS (
    SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'
  ));

CREATE INDEX IF NOT EXISTS support_cases_order_idx ON support_cases(order_id);
CREATE INDEX IF NOT EXISTS support_cases_customer_idx ON support_cases(customer_id);
CREATE INDEX IF NOT EXISTS support_cases_status_idx ON support_cases(status);

-- ── 4. RPC: reopen_payment_order ─────────────────────────────────────
CREATE OR REPLACE FUNCTION public.reopen_payment_order(
  p_order_id  UUID,
  p_admin_id  UUID,
  p_reason    TEXT DEFAULT 'إعادة فتح يدوي من الأدمن'
)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_order       payment_orders%ROWTYPE;
  v_topup_id    UUID;
  v_new_expires TIMESTAMPTZ := now() + INTERVAL '30 minutes';
BEGIN
  -- تحقق أن المُستدعي أدمن
  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id = p_admin_id AND role = 'admin') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'forbidden');
  END IF;

  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  IF v_order.status = 'confirmed' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'already_confirmed');
  END IF;

  -- أعد فتح الطلب
  UPDATE payment_orders
  SET status              = 'reopened',
      verification_status = 'waiting_for_verification',
      expires_at          = v_new_expires
  WHERE id = p_order_id;

  -- أنشئ wallet_topup_request جديدة مرتبطة بالطلب (للـ dispatch تلقائياً)
  INSERT INTO wallet_topup_requests (
    customer_id, amount, credits_requested, fingerprint_amount,
    sender_phone, sender_name, payment_method, package_id,
    notes, payment_order_id, verification_status
  )
  SELECT
    user_id, expected_amount, credits_qty, expected_amount,
    sender_phone, sender_name, 'vodafone_cash', offer_id,
    'reopened|' || p_reason || '|orig_order_id:' || p_order_id::text,
    p_order_id, 'waiting_for_verification'
  FROM payment_orders WHERE id = p_order_id
  RETURNING id INTO v_topup_id;

  -- سجّل في order_status_history
  INSERT INTO order_status_history (order_id, old_status, new_status, changed_by, reason)
  VALUES (p_order_id, v_order.status, 'reopened', p_admin_id, p_reason);

  -- سجّل في admin_audit_log
  INSERT INTO admin_audit_log (admin_id, action, target_type, target_id, details)
  VALUES (p_admin_id, 'reopen_order', 'payment_order', p_order_id,
    jsonb_build_object(
      'old_status', v_order.status,
      'new_expires', v_new_expires,
      'reason', p_reason,
      'new_topup_id', v_topup_id
    ));

  RETURN jsonb_build_object(
    'ok', true,
    'order_id', p_order_id,
    'new_topup_request_id', v_topup_id,
    'new_expires_at', v_new_expires,
    'new_status', 'reopened',
    'verification_status', 'waiting_for_verification'
  );
END;
$$;

-- ── 5. RPC: admin_manual_confirm_order ───────────────────────────────
CREATE OR REPLACE FUNCTION public.admin_manual_confirm_order(
  p_order_id    UUID,
  p_admin_id    UUID,
  p_reason      TEXT DEFAULT 'تأكيد يدوي من الأدمن',
  p_topup_id    UUID DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_order         payment_orders%ROWTYPE;
  v_profile       profiles%ROWTYPE;
  v_balance_before NUMERIC;
  v_synth_tx_id   TEXT;
BEGIN
  -- تحقق أدمن
  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id = p_admin_id AND role = 'admin') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'forbidden');
  END IF;

  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  IF v_order.status = 'confirmed' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'already_confirmed');
  END IF;

  IF v_order.status IN ('cancelled', 'duplicate') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'terminal_status', 'status', v_order.status);
  END IF;

  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'user_not_found');
  END IF;

  -- Idempotency: رقم عملية مصطنع فريد
  v_synth_tx_id := 'MANUAL-' || p_admin_id::text || '-' || p_order_id::text;
  IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE transaction_id = v_synth_tx_id) THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_manually_confirmed');
  END IF;

  v_balance_before := COALESCE(v_profile.credits_balance, 0);

  -- Atomic credit
  UPDATE profiles
  SET credits_balance = COALESCE(credits_balance, 0) + v_order.credits_qty
  WHERE id = v_order.user_id;

  -- حفظ transaction لمنع التكرار
  INSERT INTO confirmed_transactions (
    transaction_id, order_id, user_id, amount, credits_added,
    confirmed_by, device_id, notes
  ) VALUES (
    v_synth_tx_id, p_order_id, v_order.user_id,
    v_order.expected_amount, v_order.credits_qty,
    p_admin_id::text, 'manual_confirm', p_reason
  );

  -- تحديث الطلب
  UPDATE payment_orders
  SET status              = 'confirmed',
      verification_status = 'completed',
      confirmed_at        = now()
  WHERE id = p_order_id;

  -- تحديث wallet_topup_request إذا وُجد
  IF p_topup_id IS NOT NULL THEN
    UPDATE wallet_topup_requests
    SET status              = 'approved',
        verification_status = 'completed',
        reviewed_by         = p_admin_id,
        reviewed_at         = now(),
        notes               = COALESCE(notes, '') || '|manual_confirm'
    WHERE id = p_topup_id;
  END IF;

  -- سجّل order_status_history
  INSERT INTO order_status_history (order_id, old_status, new_status, changed_by, reason)
  VALUES (p_order_id, v_order.status, 'confirmed', p_admin_id, 'manual: ' || p_reason);

  -- سجّل admin_audit_log
  INSERT INTO admin_audit_log (admin_id, action, target_type, target_id, details)
  VALUES (p_admin_id, 'manual_confirm', 'payment_order', p_order_id,
    jsonb_build_object(
      'prev_status', v_order.status,
      'credits_added', v_order.credits_qty,
      'balance_before', v_balance_before,
      'balance_after', v_balance_before + v_order.credits_qty,
      'reason', p_reason,
      'synthetic_tx_id', v_synth_tx_id
    ));

  RETURN jsonb_build_object(
    'ok', true,
    'order_id', p_order_id,
    'credits_added', v_order.credits_qty,
    'balance_before', v_balance_before,
    'balance_after', v_balance_before + v_order.credits_qty,
    'transaction_id', v_synth_tx_id
  );
END;
$$;

-- ── 6. RPC: open_support_case ─────────────────────────────────────────
CREATE OR REPLACE FUNCTION public.open_support_case(
  p_order_id   UUID,
  p_admin_id   UUID,
  p_reason     TEXT,
  p_notes      TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_order  payment_orders%ROWTYPE;
  v_case_id UUID;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id = p_admin_id AND role = 'admin') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'forbidden');
  END IF;

  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  -- منع تكرار فتح قضية لنفس الطلب إذا كانت مفتوحة
  IF EXISTS (SELECT 1 FROM support_cases
             WHERE order_id = p_order_id AND status IN ('open','investigating')) THEN
    SELECT id INTO v_case_id FROM support_cases
    WHERE order_id = p_order_id AND status IN ('open','investigating')
    LIMIT 1;
    RETURN jsonb_build_object('ok', false, 'reason', 'case_already_open', 'case_id', v_case_id);
  END IF;

  INSERT INTO support_cases (
    order_id, opened_by, customer_id, reason, admin_notes,
    topup_request_id
  ) VALUES (
    p_order_id, p_admin_id, v_order.user_id, p_reason, p_notes,
    (SELECT id FROM wallet_topup_requests
     WHERE payment_order_id = p_order_id
     ORDER BY created_at DESC LIMIT 1)
  )
  RETURNING id INTO v_case_id;

  INSERT INTO admin_audit_log (admin_id, action, target_type, target_id, details)
  VALUES (p_admin_id, 'open_case', 'payment_order', p_order_id,
    jsonb_build_object('case_id', v_case_id, 'reason', p_reason));

  RETURN jsonb_build_object('ok', true, 'case_id', v_case_id);
END;
$$;

-- ── 7. RPC: get_topup_dashboard_stats ────────────────────────────────
CREATE OR REPLACE FUNCTION public.get_topup_dashboard_stats()
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_scanning   INT;
  v_confirmed  INT;
  v_failed     INT;
  v_expired    INT;
  v_offline    INT;
  v_reopened   INT;
  v_total      INT;
  v_online     BOOLEAN;
  v_last_beat  TIMESTAMPTZ;
  v_pending_q  INT;
BEGIN
  SELECT
    COUNT(*) FILTER (WHERE status IN ('scanning','pending','reopened')
                       AND verification_status = 'scanning') AS scanning,
    COUNT(*) FILTER (WHERE status = 'confirmed')             AS confirmed,
    COUNT(*) FILTER (WHERE status = 'failed')                AS failed,
    COUNT(*) FILTER (WHERE status = 'expired')               AS expired,
    COUNT(*) FILTER (WHERE verification_status = 'admin_offline') AS offline,
    COUNT(*) FILTER (WHERE status = 'reopened')              AS reopened,
    COUNT(*)                                                  AS total
  INTO v_scanning, v_confirmed, v_failed, v_expired, v_offline, v_reopened, v_total
  FROM payment_orders
  WHERE created_at >= now() - INTERVAL '48 hours';

  SELECT last_heartbeat_at, is_active,
         last_heartbeat_at >= now() - INTERVAL '90 seconds'
  INTO v_last_beat, v_online, v_online
  FROM sms_device_status
  WHERE is_active = true
  ORDER BY last_heartbeat_at DESC
  LIMIT 1;

  -- عدد الطلبات التي تنتظر الفحص
  SELECT COUNT(*) INTO v_pending_q
  FROM payment_orders
  WHERE status IN ('pending','reopened','scanning')
    AND verification_status IN ('waiting_for_verification','admin_offline','scanning')
    AND created_at >= now() - INTERVAL '48 hours';

  RETURN jsonb_build_object(
    'scanning', COALESCE(v_scanning, 0),
    'confirmed', COALESCE(v_confirmed, 0),
    'failed', COALESCE(v_failed, 0),
    'expired', COALESCE(v_expired, 0),
    'admin_offline_count', COALESCE(v_offline, 0),
    'reopened', COALESCE(v_reopened, 0),
    'total', COALESCE(v_total, 0),
    'device_online', COALESCE(v_online, false),
    'last_heartbeat_at', v_last_beat,
    'pending_queue', COALESCE(v_pending_q, 0)
  );
END;
$$;

-- ── 8. UPDATE expire_payment_orders — تعيين admin_offline لطلبات الجهاز المنفصل
CREATE OR REPLACE FUNCTION public.expire_payment_orders()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  -- انتهت مدة الطلبات المنتظرة — نُعيّن expired لكن نحتفظ بالبيانات
  UPDATE payment_orders
  SET status = 'expired'
  WHERE status IN ('awaiting_payment', 'pending', 'scanning', 'reopened')
    AND expires_at <= now();

  -- طلبات قيد الانتظار (لم تُرسل لجهاز) بعد 5 دقائق من الإنشاء — تعيين admin_offline
  UPDATE payment_orders
  SET verification_status = 'admin_offline'
  WHERE status IN ('pending', 'scanning')
    AND verification_status = 'waiting_for_verification'
    AND created_at <= now() - INTERVAL '5 minutes'
    AND NOT EXISTS (
      SELECT 1 FROM sms_device_status
      WHERE is_active = true
        AND last_heartbeat_at >= now() - INTERVAL '90 seconds'
    );

  -- نفس الشيء لـ wallet_topup_requests
  UPDATE wallet_topup_requests
  SET verification_status = 'admin_offline'
  WHERE status = 'pending'
    AND verification_status = 'waiting_for_verification'
    AND created_at <= now() - INTERVAL '5 minutes'
    AND NOT EXISTS (
      SELECT 1 FROM sms_device_status
      WHERE is_active = true
        AND last_heartbeat_at >= now() - INTERVAL '90 seconds'
    );
END;
$$;

-- ── 9. تفعيل Realtime على support_cases ──────────────────────────────
ALTER PUBLICATION supabase_realtime ADD TABLE support_cases;
