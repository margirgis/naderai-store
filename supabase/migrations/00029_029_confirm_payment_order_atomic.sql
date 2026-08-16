
-- ═══════════════════════════════════════════════════════════════
-- Migration 029: confirm_payment_order — 10-step atomic RPC
-- + security_audit_log for tamper attempts
-- + payment_order link for wallet_topup_requests dispatch
-- ═══════════════════════════════════════════════════════════════

-- 1. security_audit_log (tamper / replay attempts) ─────────────
CREATE TABLE IF NOT EXISTS security_audit_log (
  id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  event_type   TEXT        NOT NULL,  -- tamper_amount | replay_txn | reuse_order | etc.
  user_id      UUID,
  order_id     UUID,
  device_id    TEXT,
  details      JSONB,
  ip_hint      TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS security_audit_log_user_idx  ON security_audit_log(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS security_audit_log_order_idx ON security_audit_log(order_id);
CREATE INDEX IF NOT EXISTS security_audit_log_event_idx ON security_audit_log(event_type, created_at DESC);
ALTER TABLE security_audit_log ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "admin_all_security_audit" ON security_audit_log;
CREATE POLICY "admin_all_security_audit" ON security_audit_log
  FOR ALL USING (
    EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin')
  );

-- 2. confirm_payment_order RPC ─────────────────────────────────
-- Called by wallet-auto-confirm Edge Function after device scans SMS
-- Implements all 10 server-side verification checks
CREATE OR REPLACE FUNCTION confirm_payment_order(
  p_order_id        UUID,
  p_transaction_id  TEXT,
  p_received_amount NUMERIC(12,2),
  p_sender_phone    TEXT       DEFAULT NULL,
  p_sender_name     TEXT       DEFAULT NULL,
  p_sms_timestamp   TIMESTAMPTZ DEFAULT NULL,
  p_device_id       TEXT       DEFAULT NULL,
  p_scan_id         TEXT       DEFAULT NULL,
  p_idempotency_key TEXT       DEFAULT NULL,
  p_sms_body        TEXT       DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_order        payment_orders%ROWTYPE;
  v_topup        wallet_topup_requests%ROWTYPE;
  v_profile      profiles%ROWTYPE;
  v_balance_before NUMERIC;
  SMS_WINDOW     CONSTANT INTERVAL := INTERVAL '5 minutes';  -- allow SMS up to 5min before order
  MAX_SMS_AGE    CONSTANT INTERVAL := INTERVAL '20 minutes'; -- SMS must not be older than 20 min
BEGIN
  -- ─── Idempotency check ────────────────────────────────────
  IF p_idempotency_key IS NOT NULL THEN
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions
      WHERE order_id = p_order_id
        AND device_id = p_device_id
    ) THEN
      RETURN jsonb_build_object(
        'ok', true, 'idempotent', true,
        'reason', 'already_confirmed', 'order_id', p_order_id
      );
    END IF;
  END IF;

  -- ─── Check 1: Order exists ────────────────────────────────
  SELECT * INTO v_order FROM payment_orders
  WHERE id = p_order_id FOR UPDATE;

  IF NOT FOUND THEN
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('invalid_order_id', p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_not_found');
  END IF;

  -- ─── Check 2: Order belongs to a real user ────────────────
  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'user_not_found');
  END IF;

  -- ─── Check 3: Order not expired ──────────────────────────
  IF v_order.expires_at <= now() THEN
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_expired', 'order_id', p_order_id);
  END IF;

  -- ─── Check 4: Order not already confirmed ────────────────
  IF v_order.status = 'confirmed' THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('reuse_confirmed_order', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id));
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_confirmed');
  END IF;

  -- ─── Check 5: Order not cancelled ────────────────────────
  IF v_order.status IN ('cancelled', 'failed', 'expired', 'duplicate') THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('reuse_terminal_order', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('status', v_order.status, 'transaction_id', p_transaction_id));
    RETURN jsonb_build_object('ok', false, 'scan_status', v_order.status,
      'reason', 'order_in_terminal_status');
  END IF;

  -- ─── Check 6: transaction_id not used before ─────────────
  IF p_transaction_id IS NOT NULL AND p_transaction_id NOT LIKE 'DEVICE-%' AND p_transaction_id NOT LIKE 'SMS-%' THEN
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions WHERE transaction_id = p_transaction_id
    ) THEN
      -- Log tamper attempt
      INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
      VALUES ('replay_transaction_id', v_order.user_id, p_order_id, p_device_id,
        jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));

      -- Mark this order as duplicate
      UPDATE payment_orders SET status = 'duplicate' WHERE id = p_order_id;

      -- Update linked topup request
      UPDATE wallet_topup_requests
      SET scan_status = 'duplicate',
          failure_reason = 'رقم العملية مستخدم من قبل - ' || p_transaction_id
      WHERE notes LIKE '%' || p_order_id::text || '%'
        AND status NOT IN ('approved', 'rejected');

      RETURN jsonb_build_object(
        'ok', false,
        'scan_status', 'duplicate',
        'reason', 'duplicate_transaction_id',
        'message', 'تم رفض العملية: رقم العملية مستخدم من قبل.'
      );
    END IF;
  END IF;

  -- ─── Check 7: SMS timestamp within acceptable window ─────
  IF p_sms_timestamp IS NOT NULL THEN
    -- SMS must not be more than 5 min BEFORE order creation (allow slight clock skew)
    IF p_sms_timestamp < (v_order.created_at - SMS_WINDOW) THEN
      INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
      VALUES ('old_sms_reuse', v_order.user_id, p_order_id, p_device_id,
        jsonb_build_object('sms_timestamp', p_sms_timestamp,
          'order_created_at', v_order.created_at, 'transaction_id', p_transaction_id));

      UPDATE payment_orders SET status = 'failed' WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
        'reason', 'sms_too_old',
        'message', 'رسالة SMS أقدم من وقت إنشاء الطلب.');
    END IF;
    -- SMS must not be more than 20 min old total
    IF p_sms_timestamp < (now() - MAX_SMS_AGE) THEN
      UPDATE payment_orders SET status = 'failed' WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
        'reason', 'sms_expired',
        'message', 'الرسالة منتهية الصلاحية.');
    END IF;
  END IF;

  -- ─── Check 8: Amount matches expected_amount ─────────────
  IF ABS(p_received_amount - v_order.expected_amount) > 0.01 THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('amount_mismatch', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('expected', v_order.expected_amount, 'received', p_received_amount));

    UPDATE payment_orders SET status = 'amount_mismatch' WHERE id = p_order_id;
    UPDATE wallet_topup_requests
    SET scan_status = 'amount_mismatch',
        failure_reason = 'المبلغ غير مطابق: مطلوب ' || v_order.expected_amount || ' تم استلام ' || p_received_amount
    WHERE notes LIKE '%' || p_order_id::text || '%'
      AND status NOT IN ('approved', 'rejected');

    RETURN jsonb_build_object(
      'ok', false, 'scan_status', 'amount_mismatch',
      'reason', 'amount_mismatch',
      'expected', v_order.expected_amount,
      'received', p_received_amount
    );
  END IF;

  -- ─── Check 9: No duplicate active confirmation ────────────
  IF EXISTS (
    SELECT 1 FROM confirmed_transactions WHERE order_id = p_order_id
  ) THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_in_registry');
  END IF;

  -- ════════════════════════════════════════════════════════════
  -- Check 10 passed. All verified → ATOMIC CONFIRMATION
  -- ════════════════════════════════════════════════════════════

  -- Step A: Record transaction_id in registry (UNIQUE constraint is final guard)
  BEGIN
    INSERT INTO confirmed_transactions(
      transaction_id, order_id, user_id,
      sender_phone, sender_name,
      amount, device_id, confirmed_at, status
    ) VALUES (
      COALESCE(p_transaction_id, 'AUTO-' || p_order_id::text),
      p_order_id,
      v_order.user_id,
      p_sender_phone,
      p_sender_name,
      p_received_amount,
      p_device_id,
      now(),
      'confirmed'
    );
  EXCEPTION WHEN unique_violation THEN
    -- Race condition: another process just confirmed this transaction
    UPDATE payment_orders SET status = 'duplicate' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'duplicate',
      'reason', 'race_condition_duplicate');
  END;

  -- Step B: Mark payment_order as confirmed
  UPDATE payment_orders
  SET status = 'confirmed', confirmed_at = now()
  WHERE id = p_order_id;

  -- Step C: Get balance before credit
  SELECT wallet_balance INTO v_balance_before FROM profiles WHERE id = v_order.user_id;

  -- Step D: Add credits atomically
  UPDATE profiles
  SET wallet_balance = wallet_balance + v_order.credits_qty
  WHERE id = v_order.user_id;

  -- Step E: Wallet transaction ledger
  INSERT INTO wallet_transactions(
    customer_id, type, amount,
    balance_before, balance_after,
    reason, reference
  ) VALUES (
    v_order.user_id,
    'credit',
    v_order.credits_qty,
    v_balance_before,
    v_balance_before + v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #' || v_order.order_number,
    'PAY-ORDER-' || p_order_id::text
  );

  -- Step F: Update linked topup request to approved
  UPDATE wallet_topup_requests
  SET status = 'approved',
      scan_status = 'approved',
      transaction_id = p_transaction_id,
      sender_phone = COALESCE(p_sender_phone, sender_phone),
      sender_name  = COALESCE(p_sender_name, sender_name),
      matched_automatically = true,
      processed_at = now()
  WHERE notes LIKE '%' || p_order_id::text || '%'
    AND status NOT IN ('approved', 'rejected');

  -- Step G: Admin audit log
  INSERT INTO admin_audit_log(
    admin_id, action, target_id, target_type,
    details, created_at
  ) VALUES (
    '00000000-0000-0000-0000-000000000000'::uuid,
    'auto_confirm_payment_order',
    p_order_id,
    'payment_order',
    jsonb_build_object(
      'transaction_id', p_transaction_id,
      'credits_added', v_order.credits_qty,
      'amount', p_received_amount,
      'user_id', v_order.user_id,
      'device_id', p_device_id
    ),
    now()
  );

  -- Step H: User notification
  INSERT INTO notifications(
    user_id, type, title, body, order_id
  ) VALUES (
    v_order.user_id,
    'wallet_topup',
    'تم تأكيد طلب الشحن ✅',
    'تم إضافة ' || v_order.credits_qty || ' Credit إلى محفظتك — طلب #' || v_order.order_number,
    NULL
  );

  -- Step I: Order status history
  INSERT INTO order_status_history(
    request_id, old_status, new_status, changed_by, reason
  ) VALUES (
    NULL,
    'scanning',
    'confirmed',
    COALESCE(p_device_id, 'system'),
    'تأكيد تلقائي - معاملة: ' || COALESCE(p_transaction_id, 'N/A')
  );

  RETURN jsonb_build_object(
    'ok', true,
    'scan_status', 'confirmed',
    'order_id', p_order_id,
    'order_number', v_order.order_number,
    'credits_added', v_order.credits_qty,
    'transaction_id', p_transaction_id,
    'balance_before', v_balance_before,
    'balance_after', v_balance_before + v_order.credits_qty
  );
END;
$$;

-- 3. Link pending_tasks with payment_orders ────────────────────
-- When dispatching to Android, include order_id and expires_at
ALTER TABLE pending_tasks
  ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES payment_orders(id) ON DELETE SET NULL;
ALTER TABLE pending_tasks
  ADD COLUMN IF NOT EXISTS order_expires_at TIMESTAMPTZ;
