
-- ══════════════════════════════════════════════════════════════════════════════
-- Migration 00049: Financial Architecture v2
-- 1. payment_transactions ledger (central, UNIQUE transaction_id)
-- 2. financial_audit_log (every financial event)
-- 3. RPC: atomic_process_payment (7-step: check→lock→validate→confirm→credit→mark→log)
-- 4. RPC: get_payment_order_full (full order details for admin)
-- 5. Realtime: ensure payment_orders published (REPLICA IDENTITY FULL)
-- 6. Trigger: notify on payment_orders INSERT/UPDATE
-- 7. RPC: get_all_payment_orders (all statuses, no filter)
-- ══════════════════════════════════════════════════════════════════════════════

-- ── 1. payment_transactions ledger ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_transactions (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id   TEXT        NOT NULL,
  order_id         UUID        REFERENCES payment_orders(id) ON DELETE SET NULL,
  topup_request_id UUID        REFERENCES wallet_topup_requests(id) ON DELETE SET NULL,
  sender_phone     TEXT,
  sender_name      TEXT,
  amount           NUMERIC(12,2) NOT NULL,
  status           TEXT        NOT NULL DEFAULT 'accepted'
    CHECK (status IN ('accepted','rejected','duplicate','reversed')),
  rejection_reason TEXT,
  device_id        TEXT,
  sms_body         TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  confirmed_at     TIMESTAMPTZ
);

-- THE critical constraint: one transaction_id can only ever be used once
ALTER TABLE payment_transactions
  DROP CONSTRAINT IF EXISTS payment_transactions_tx_id_unique;
ALTER TABLE payment_transactions
  ADD CONSTRAINT payment_transactions_tx_id_unique UNIQUE (transaction_id);

CREATE INDEX IF NOT EXISTS payment_transactions_order_idx    ON payment_transactions(order_id);
CREATE INDEX IF NOT EXISTS payment_transactions_status_idx   ON payment_transactions(status);
CREATE INDEX IF NOT EXISTS payment_transactions_created_idx  ON payment_transactions(created_at DESC);

ALTER TABLE payment_transactions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "admin_all_payment_transactions" ON payment_transactions;
CREATE POLICY "admin_all_payment_transactions" ON payment_transactions
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- ── 2. financial_audit_log ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS financial_audit_log (
  id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type       TEXT        NOT NULL,
  order_id         UUID,
  transaction_id   TEXT,
  topup_request_id UUID,
  actor            TEXT,       -- 'system' | 'admin:<uuid>' | 'device:<device_id>'
  amount           NUMERIC(12,2),
  metadata         JSONB       NOT NULL DEFAULT '{}',
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS fin_audit_order_idx       ON financial_audit_log(order_id);
CREATE INDEX IF NOT EXISTS fin_audit_tx_idx          ON financial_audit_log(transaction_id);
CREATE INDEX IF NOT EXISTS fin_audit_event_type_idx  ON financial_audit_log(event_type);
CREATE INDEX IF NOT EXISTS fin_audit_created_idx     ON financial_audit_log(created_at DESC);

ALTER TABLE financial_audit_log ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "admin_all_financial_audit" ON financial_audit_log;
CREATE POLICY "admin_all_financial_audit" ON financial_audit_log
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- ── Helper: log financial event ───────────────────────────────────────────────
CREATE OR REPLACE FUNCTION log_financial_event(
  p_event_type     TEXT,
  p_order_id       UUID        DEFAULT NULL,
  p_transaction_id TEXT        DEFAULT NULL,
  p_topup_id       UUID        DEFAULT NULL,
  p_actor          TEXT        DEFAULT 'system',
  p_amount         NUMERIC     DEFAULT NULL,
  p_metadata       JSONB       DEFAULT '{}'
)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  INSERT INTO financial_audit_log(event_type, order_id, transaction_id, topup_request_id, actor, amount, metadata)
  VALUES (p_event_type, p_order_id, p_transaction_id, p_topup_id, p_actor, p_amount, p_metadata);
END;
$$;

-- ── 3. RPC: atomic_process_payment ───────────────────────────────────────────
-- 7-step atomic flow:
--   1. Check transaction_id not already used
--   2. Lock payment_order row FOR UPDATE
--   3. Validate order status (not terminal)
--   4. Validate exact amount
--   5. Validate sender_phone (if contract exists)
--   6. Confirm order → 'confirmed', add credit to wallet
--   7. Insert payment_transaction record + mark confirmed_at
CREATE OR REPLACE FUNCTION public.atomic_process_payment(
  p_order_id        UUID,
  p_transaction_id  TEXT,
  p_received_amount NUMERIC,
  p_sender_phone    TEXT        DEFAULT NULL,
  p_sender_name     TEXT        DEFAULT NULL,
  p_device_id       TEXT        DEFAULT NULL,
  p_sms_body        TEXT        DEFAULT NULL,
  p_actor           TEXT        DEFAULT 'system',
  p_topup_id        UUID        DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_order          payment_orders%ROWTYPE;
  v_profile        profiles%ROWTYPE;
  v_balance_before NUMERIC;
  v_now            TIMESTAMPTZ := now();
BEGIN

  -- ── STEP 1: Check transaction_id uniqueness ─────────────────────────────
  IF p_transaction_id IS NOT NULL
     AND p_transaction_id NOT LIKE 'MANUAL-%'
     AND p_transaction_id NOT LIKE 'ADMIN-%'
  THEN
    -- Check in new payment_transactions table
    IF EXISTS (SELECT 1 FROM payment_transactions WHERE transaction_id = p_transaction_id AND status = 'accepted') THEN
      -- Is it the same order (idempotent retry)?
      IF EXISTS (SELECT 1 FROM payment_transactions WHERE transaction_id = p_transaction_id AND order_id = p_order_id) THEN
        PERFORM log_financial_event('transaction_idempotent_retry', p_order_id, p_transaction_id, p_topup_id,
          p_actor, p_received_amount, jsonb_build_object('device_id', p_device_id));
        RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_confirmed');
      END IF;
      -- Different order = replay attack / fraud
      PERFORM log_financial_event('transaction_reused_rejected', p_order_id, p_transaction_id, p_topup_id,
        p_actor, p_received_amount,
        jsonb_build_object('device_id', p_device_id, 'reused_for_order', p_order_id));
      -- Mark the new order as duplicate
      UPDATE payment_orders SET status = 'duplicate',
        verification_status = 'no_match',
        failure_reason = 'رقم العملية مستخدم في طلب آخر: ' || p_transaction_id
      WHERE id = p_order_id;
      RETURN jsonb_build_object(
        'ok', false, 'scan_status', 'duplicate',
        'reason', 'duplicate_transaction_id',
        'message', 'رقم العملية ' || p_transaction_id || ' تم استخدامه مسبقاً'
      );
    END IF;
    -- Also check legacy confirmed_transactions table
    IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE transaction_id = p_transaction_id) THEN
      IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE transaction_id = p_transaction_id AND order_id = p_order_id) THEN
        RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_confirmed_legacy');
      END IF;
      PERFORM log_financial_event('transaction_reused_legacy', p_order_id, p_transaction_id, p_topup_id,
        p_actor, p_received_amount, jsonb_build_object('source', 'confirmed_transactions'));
      UPDATE payment_orders SET status = 'duplicate',
        failure_reason = 'رقم العملية مستخدم (legacy): ' || p_transaction_id
      WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'duplicate', 'reason', 'duplicate_transaction_id_legacy');
    END IF;
  END IF;

  -- ── STEP 2: Lock the order row ──────────────────────────────────────────
  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    PERFORM log_financial_event('order_not_found', p_order_id, p_transaction_id, p_topup_id,
      p_actor, p_received_amount, '{}');
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  -- ── STEP 3: Validate order status (not terminal) ────────────────────────
  IF v_order.status = 'confirmed' THEN
    PERFORM log_financial_event('order_already_confirmed', p_order_id, p_transaction_id, p_topup_id,
      p_actor, p_received_amount, '{}');
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_confirmed');
  END IF;
  IF v_order.status IN ('cancelled', 'failed', 'duplicate') THEN
    PERFORM log_financial_event('order_terminal_state', p_order_id, p_transaction_id, p_topup_id,
      p_actor, p_received_amount, jsonb_build_object('order_status', v_order.status));
    RETURN jsonb_build_object('ok', false, 'reason', 'order_in_terminal_state', 'status', v_order.status);
  END IF;
  IF v_order.expires_at IS NOT NULL AND v_order.expires_at <= v_now AND v_order.status NOT IN ('reopened') THEN
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    PERFORM log_financial_event('order_expired_on_confirm', p_order_id, p_transaction_id, p_topup_id,
      p_actor, p_received_amount, '{}');
    RETURN jsonb_build_object('ok', false, 'reason', 'order_expired');
  END IF;

  -- ── STEP 4: Validate exact amount ───────────────────────────────────────
  IF p_received_amount <> v_order.expected_amount THEN
    PERFORM log_financial_event('amount_mismatch', p_order_id, p_transaction_id, p_topup_id,
      p_actor, p_received_amount,
      jsonb_build_object('expected', v_order.expected_amount, 'received', p_received_amount));
    UPDATE payment_orders SET
      verification_status = 'no_match',
      failure_reason = 'مبلغ غير مطابق: تم استلام ' || p_received_amount || ' بدلاً من ' || v_order.expected_amount
    WHERE id = p_order_id;
    RETURN jsonb_build_object(
      'ok', false, 'scan_status', 'amount_mismatch',
      'reason', 'amount_mismatch',
      'expected', v_order.expected_amount,
      'received', p_received_amount
    );
  END IF;

  -- ── STEP 5: Validate sender_phone (if contract exists) ─────────────────
  IF v_order.sender_phone IS NOT NULL AND p_sender_phone IS NOT NULL THEN
    IF v_order.sender_phone <> p_sender_phone THEN
      PERFORM log_financial_event('sender_phone_mismatch', p_order_id, p_transaction_id, p_topup_id,
        p_actor, p_received_amount,
        jsonb_build_object('expected_phone', v_order.sender_phone, 'received_phone', p_sender_phone));
      UPDATE payment_orders SET
        verification_status = 'no_match',
        failure_reason = 'رقم المحوّل غير مطابق'
      WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed', 'reason', 'sender_phone_mismatch');
    END IF;
  END IF;

  -- ── STEP 6: Confirm order + add credit atomically ───────────────────────
  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'user_not_found');
  END IF;
  v_balance_before := COALESCE(v_profile.wallet_balance, 0);

  UPDATE payment_orders SET
    status              = 'confirmed',
    verification_status = 'completed',
    transaction_id      = p_transaction_id,
    confirmed_at        = v_now,
    failure_reason      = NULL
  WHERE id = p_order_id;

  UPDATE profiles SET
    wallet_balance  = COALESCE(wallet_balance, 0) + COALESCE(v_order.expected_amount, 0),
    credits_balance = COALESCE(credits_balance, 0) + COALESCE(v_order.credits_qty, 0)
  WHERE id = v_order.user_id;

  -- Also update topup_request if linked
  IF p_topup_id IS NOT NULL THEN
    UPDATE wallet_topup_requests SET
      status              = 'approved',
      verification_status = 'completed',
      processed_at        = v_now,
      scan_status         = 'success',
      transaction_id      = p_transaction_id
    WHERE id = p_topup_id;
  END IF;

  -- ── STEP 7: Record in payment_transactions + legacy confirmed_transactions
  INSERT INTO payment_transactions(
    transaction_id, order_id, topup_request_id,
    sender_phone, sender_name, amount, status,
    device_id, sms_body, confirmed_at
  ) VALUES (
    p_transaction_id, p_order_id, p_topup_id,
    p_sender_phone, p_sender_name, p_received_amount, 'accepted',
    p_device_id, p_sms_body, v_now
  )
  ON CONFLICT (transaction_id) DO NOTHING;

  -- Also write to legacy table for backward compat
  INSERT INTO confirmed_transactions(
    transaction_id, order_id, user_id, amount,
    sender_phone, sender_name, confirmed_at
  ) VALUES (
    COALESCE(p_transaction_id, 'PROC-' || gen_random_uuid()::text),
    p_order_id, v_order.user_id, p_received_amount,
    p_sender_phone, p_sender_name, v_now
  ) ON CONFLICT (transaction_id) DO NOTHING;

  -- Audit log
  PERFORM log_financial_event('payment_confirmed', p_order_id, p_transaction_id, p_topup_id,
    p_actor, p_received_amount,
    jsonb_build_object(
      'user_id', v_order.user_id,
      'credits_qty', v_order.credits_qty,
      'balance_before', v_balance_before,
      'balance_after', v_balance_before + COALESCE(v_order.expected_amount, 0),
      'device_id', p_device_id,
      'sender_phone', p_sender_phone
    ));

  -- Admin notification
  PERFORM create_admin_notification(
    p_title        := '✅ تم تأكيد طلب شحن',
    p_message      := 'الطلب #' || COALESCE(v_order.order_number::text, p_order_id::text)
                      || ' — ' || p_received_amount || ' جنيه — ' || COALESCE(p_sender_phone, ''),
    p_event_type   := 'payment_confirmed',
    p_reference_id := p_order_id::text,
    p_device_id    := p_device_id
  );

  RETURN jsonb_build_object(
    'ok', true,
    'scan_status', 'success',
    'order_id', p_order_id,
    'transaction_id', p_transaction_id,
    'amount_confirmed', p_received_amount,
    'credits_added', v_order.credits_qty,
    'balance_before', v_balance_before,
    'balance_after', v_balance_before + COALESCE(v_order.expected_amount, 0)
  );
END;
$$;

-- ── 4. RPC: get_all_payment_orders ────────────────────────────────────────────
-- Returns ALL orders regardless of status (for admin dashboard, never hides data)
CREATE OR REPLACE FUNCTION public.get_all_payment_orders(
  p_limit  INT DEFAULT 200,
  p_offset INT DEFAULT 0,
  p_status TEXT DEFAULT NULL  -- NULL = all statuses
)
RETURNS TABLE (
  id               UUID,
  order_number     INT,
  user_id          UUID,
  status           TEXT,
  verification_status TEXT,
  expected_amount  NUMERIC,
  credits_qty      INT,
  sender_phone     TEXT,
  sender_name      TEXT,
  transaction_id   TEXT,
  failure_reason   TEXT,
  payment_method   TEXT,
  created_at       TIMESTAMPTZ,
  confirmed_at     TIMESTAMPTZ,
  expires_at       TIMESTAMPTZ,
  customer_email   TEXT,
  customer_name    TEXT,
  customer_phone   TEXT,
  wallet_balance   NUMERIC,
  credits_balance  INT
)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin') THEN
    RAISE EXCEPTION 'forbidden';
  END IF;
  RETURN QUERY
  SELECT
    po.id, po.order_number, po.user_id,
    po.status, po.verification_status,
    po.expected_amount, po.credits_qty,
    po.sender_phone, po.sender_name,
    po.transaction_id, po.failure_reason,
    po.payment_method,
    po.created_at, po.confirmed_at, po.expires_at,
    pr.email, pr.full_name, pr.phone,
    pr.wallet_balance, pr.credits_balance
  FROM payment_orders po
  LEFT JOIN profiles pr ON pr.id = po.user_id
  WHERE (p_status IS NULL OR po.status = p_status)
  ORDER BY po.created_at DESC
  LIMIT p_limit OFFSET p_offset;
END;
$$;

-- ── 5. REPLICA IDENTITY FULL on payment_orders (needed for Realtime UPDATE) ──
ALTER TABLE payment_orders REPLICA IDENTITY FULL;
ALTER TABLE wallet_topup_requests REPLICA IDENTITY FULL;

-- Ensure payment_orders is in supabase_realtime publication
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'payment_orders'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE payment_orders;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables
    WHERE pubname = 'supabase_realtime' AND tablename = 'financial_audit_log'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE financial_audit_log;
  END IF;
END $$;

-- ── 6. Update complete_device_task to use atomic_process_payment ──────────────
-- Patch: when task_result has status='success', route through atomic_process_payment
-- (This wraps the existing complete_device_task by adding a call in confirm_payment_order)
-- The confirm_payment_order already calls the ledger — we add a bridge so new flow always
-- writes to payment_transactions too.
CREATE OR REPLACE FUNCTION public.bridge_confirm_to_ledger()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  -- When payment_orders.status changes to 'confirmed', ensure payment_transactions record exists
  IF NEW.status = 'confirmed' AND (OLD.status IS DISTINCT FROM 'confirmed') THEN
    INSERT INTO payment_transactions(
      transaction_id, order_id, sender_phone, sender_name, amount, status, confirmed_at
    ) VALUES (
      COALESCE(NEW.transaction_id, 'AUTO-' || NEW.id::text),
      NEW.id,
      NEW.sender_phone,
      NEW.sender_name,
      NEW.expected_amount,
      'accepted',
      COALESCE(NEW.confirmed_at, now())
    ) ON CONFLICT (transaction_id) DO UPDATE
      SET confirmed_at = EXCLUDED.confirmed_at,
          order_id = EXCLUDED.order_id;

    -- Audit log
    INSERT INTO financial_audit_log(event_type, order_id, transaction_id, actor, amount, metadata)
    VALUES (
      'payment_confirmed_via_trigger',
      NEW.id,
      COALESCE(NEW.transaction_id, 'AUTO-' || NEW.id::text),
      'trigger',
      NEW.expected_amount,
      jsonb_build_object('order_number', NEW.order_number, 'user_id', NEW.user_id)
    );
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_bridge_confirm_to_ledger ON payment_orders;
CREATE TRIGGER trg_bridge_confirm_to_ledger
  AFTER UPDATE ON payment_orders
  FOR EACH ROW EXECUTE FUNCTION bridge_confirm_to_ledger();

-- ── 7. Trigger: audit on wallet_topup_requests approval ──────────────────────
CREATE OR REPLACE FUNCTION public.bridge_topup_confirm_to_ledger()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  IF NEW.status = 'approved' AND (OLD.status IS DISTINCT FROM 'approved') THEN
    INSERT INTO financial_audit_log(event_type, order_id, transaction_id, actor, amount, metadata)
    VALUES (
      'topup_approved',
      NEW.payment_order_id,
      NEW.transaction_id,
      'system',
      NEW.amount,
      jsonb_build_object('topup_id', NEW.id, 'customer_id', NEW.customer_id)
    ) ON CONFLICT DO NOTHING;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_bridge_topup_confirm ON wallet_topup_requests;
CREATE TRIGGER trg_bridge_topup_confirm
  AFTER UPDATE ON wallet_topup_requests
  FOR EACH ROW EXECUTE FUNCTION bridge_topup_confirm_to_ledger();
