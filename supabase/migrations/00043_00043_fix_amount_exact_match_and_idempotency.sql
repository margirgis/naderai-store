-- ═══════════════════════════════════════════════════════════════════════════
-- Migration 00043: Fix critical financial bugs
-- 1. confirm_payment_order: amount must match EXACTLY (±0.01 → ±0.00)
-- 2. confirm_payment_order: idempotency must check transaction_id, not device_id
-- 3. atomic_confirm_topup: add mandatory amount validation
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. Replace confirm_payment_order with exact amount matching ───────────
CREATE OR REPLACE FUNCTION public.confirm_payment_order(
  p_order_id        uuid,
  p_transaction_id  text,
  p_received_amount numeric,
  p_sender_phone    text    DEFAULT NULL,
  p_sender_name     text    DEFAULT NULL,
  p_sms_timestamp   timestamptz DEFAULT NULL,
  p_device_id       text    DEFAULT NULL,
  p_scan_id         text    DEFAULT NULL,
  p_idempotency_key text    DEFAULT NULL,
  p_sms_body        text    DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  v_order        payment_orders%ROWTYPE;
  v_profile      profiles%ROWTYPE;
  v_balance_before NUMERIC;
  SMS_WINDOW     CONSTANT INTERVAL := INTERVAL '5 minutes';
  MAX_SMS_AGE    CONSTANT INTERVAL := INTERVAL '30 minutes';
BEGIN
  -- ── 1. Idempotency: check by transaction_id (not device_id) ─────────────
  -- If this exact transaction_id was already confirmed for ANY order, reject duplicate.
  IF p_transaction_id IS NOT NULL
     AND p_transaction_id NOT LIKE 'DEVICE-%'
     AND p_transaction_id NOT LIKE 'SMS-%'
     AND p_transaction_id NOT LIKE 'AUTO-%'
  THEN
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions
      WHERE transaction_id = p_transaction_id
    ) THEN
      -- Check if it's for the same order (idempotent retry) or a different order (fraud)
      IF EXISTS (
        SELECT 1 FROM confirmed_transactions
        WHERE transaction_id = p_transaction_id AND order_id = p_order_id
      ) THEN
        RETURN jsonb_build_object(
          'ok', true, 'idempotent', true,
          'reason', 'already_confirmed', 'order_id', p_order_id
        );
      ELSE
        -- Different order using same transaction_id = replay attack
        INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
        SELECT 'replay_transaction_id_cross_order', v_order.user_id, p_order_id, p_device_id,
          jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount)
        FROM payment_orders WHERE id = p_order_id;

        UPDATE payment_orders SET status = 'duplicate' WHERE id = p_order_id;
        RETURN jsonb_build_object(
          'ok', false, 'scan_status', 'duplicate',
          'reason', 'duplicate_transaction_id_cross_order',
          'message', 'رقم العملية مستخدم في طلب آخر'
        );
      END IF;
    END IF;
  END IF;

  -- ── 2. Load & lock the order ────────────────────────────────────────────
  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('invalid_order_id', p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed', 'reason', 'order_not_found');
  END IF;

  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed', 'reason', 'user_not_found');
  END IF;

  -- ── 3. Expiry check ─────────────────────────────────────────────────────
  IF v_order.expires_at <= now() THEN
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_expired', 'order_id', p_order_id);
  END IF;

  -- ── 4. Terminal status check ─────────────────────────────────────────────
  IF v_order.status = 'confirmed' THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('reuse_confirmed_order', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id));
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_confirmed');
  END IF;

  IF v_order.status IN ('cancelled', 'failed', 'expired', 'duplicate') THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('reuse_terminal_order', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('status', v_order.status, 'transaction_id', p_transaction_id));
    RETURN jsonb_build_object('ok', false, 'scan_status', v_order.status,
      'reason', 'order_in_terminal_status');
  END IF;

  -- ── 5. SMS timestamp checks ──────────────────────────────────────────────
  IF p_sms_timestamp IS NOT NULL THEN
    IF p_sms_timestamp < (v_order.created_at - SMS_WINDOW) THEN
      INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
      VALUES ('old_sms_reuse', v_order.user_id, p_order_id, p_device_id,
        jsonb_build_object('sms_timestamp', p_sms_timestamp,
          'order_created_at', v_order.created_at, 'transaction_id', p_transaction_id));
      UPDATE payment_orders SET status = 'failed' WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
        'reason', 'sms_too_old', 'message', 'رسالة SMS أقدم من وقت إنشاء الطلب.');
    END IF;
    IF p_sms_timestamp < (now() - MAX_SMS_AGE) THEN
      UPDATE payment_orders SET status = 'failed' WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
        'reason', 'sms_expired', 'message', 'الرسالة منتهية الصلاحية.');
    END IF;
  END IF;

  -- ── 6. EXACT amount match (P0 critical fix) ──────────────────────────────
  -- Amount must match to the exact piaster (0.01 EGP tolerance for float precision only)
  -- This replaces the previous ±0.50 window which allowed financial fraud.
  IF ROUND(p_received_amount::numeric, 2) != ROUND(v_order.expected_amount::numeric, 2) THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('amount_mismatch', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object(
        'expected', v_order.expected_amount,
        'received', p_received_amount,
        'diff', ABS(p_received_amount - v_order.expected_amount)
      ));
    UPDATE payment_orders SET status = 'amount_mismatch' WHERE id = p_order_id;
    UPDATE wallet_topup_requests
    SET scan_status = 'manual_review',
        failure_reason = 'المبلغ غير مطابق: مطلوب ' || v_order.expected_amount || ' تم استلام ' || p_received_amount
    WHERE notes LIKE '%' || p_order_id::text || '%'
      AND status NOT IN ('approved', 'rejected');
    RETURN jsonb_build_object(
      'ok', false, 'scan_status', 'manual_review',
      'reason', 'amount_mismatch',
      'expected', v_order.expected_amount,
      'received', p_received_amount
    );
  END IF;

  -- ── 7. Final idempotency guard: already in confirmed_transactions ────────
  IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE order_id = p_order_id) THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_in_registry');
  END IF;

  -- ── 8. Atomic insert into confirmed_transactions ─────────────────────────
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
    UPDATE payment_orders SET status = 'duplicate' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'duplicate',
      'reason', 'race_condition_duplicate');
  END;

  -- ── 9. Confirm order & credit wallet ────────────────────────────────────
  UPDATE payment_orders
  SET status = 'confirmed', confirmed_at = now()
  WHERE id = p_order_id;

  SELECT wallet_balance INTO v_balance_before FROM profiles WHERE id = v_order.user_id;

  UPDATE profiles
  SET wallet_balance = wallet_balance + v_order.credits_qty
  WHERE id = v_order.user_id;

  INSERT INTO wallet_transactions(
    customer_id, type, amount,
    balance_before, balance_after,
    reason, reference
  ) VALUES (
    v_order.user_id, 'credit', v_order.credits_qty,
    v_balance_before, v_balance_before + v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #' || v_order.order_number,
    'PAY-ORDER-' || p_order_id::text
  );

  UPDATE wallet_topup_requests
  SET status = 'approved', scan_status = 'approved',
      transaction_id = p_transaction_id,
      sender_phone = COALESCE(p_sender_phone, sender_phone),
      sender_name  = COALESCE(p_sender_name, sender_name),
      matched_automatically = true,
      processed_at = now()
  WHERE notes LIKE '%' || p_order_id::text || '%'
    AND status NOT IN ('approved', 'rejected');

  INSERT INTO admin_audit_log(admin_id, action, target_id, target_type, details, created_at)
  VALUES (
    '00000000-0000-0000-0000-000000000000'::uuid,
    'auto_confirm_payment_order', p_order_id, 'payment_order',
    jsonb_build_object(
      'transaction_id', p_transaction_id,
      'credits_added', v_order.credits_qty,
      'amount', p_received_amount,
      'user_id', v_order.user_id,
      'device_id', p_device_id
    ),
    now()
  );

  INSERT INTO notifications(user_id, type, title, body, order_id)
  VALUES (
    v_order.user_id, 'wallet_topup',
    'تم تأكيد طلب الشحن ✅',
    'تم إضافة ' || v_order.credits_qty || ' Credit إلى محفظتك — طلب #' || v_order.order_number,
    NULL
  );

  INSERT INTO order_status_history(request_id, old_status, new_status, changed_by, reason)
  VALUES (NULL, 'scanning', 'confirmed', COALESCE(p_device_id, 'system'),
    'تأكيد تلقائي - معاملة: ' || COALESCE(p_transaction_id, 'N/A'));

  RETURN jsonb_build_object(
    'ok', true, 'scan_status', 'confirmed',
    'order_id', p_order_id,
    'order_number', v_order.order_number,
    'credits_added', v_order.credits_qty,
    'transaction_id', p_transaction_id,
    'balance_before', v_balance_before,
    'balance_after', v_balance_before + v_order.credits_qty
  );
END;
$function$;

-- ── 2. Fix atomic_confirm_topup: add mandatory exact amount validation ─────
CREATE OR REPLACE FUNCTION public.atomic_confirm_topup(
  p_order_id        uuid,
  p_transaction_id  text,
  p_sender_phone    text,
  p_sender_name     text,
  p_amount          numeric,
  p_receiver_wallet text       DEFAULT NULL,
  p_transaction_time timestamptz DEFAULT NULL,
  p_device_id       text       DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  req             RECORD;
  credits_to_add  NUMERIC;
  current_balance NUMERIC;
  new_balance     NUMERIC;
BEGIN
  SELECT * INTO req FROM wallet_topup_requests WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  IF req.status = 'approved' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'reason', 'already_confirmed', 'order_id', p_order_id);
  END IF;

  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'invalid_order_status',
      'current_status', req.status);
  END IF;

  -- ── Exact amount validation (P0 fix) ────────────────────────────────────
  -- Use fingerprint_amount if present, otherwise amount.
  -- Match must be exact to the piaster.
  DECLARE
    v_expected NUMERIC := ROUND(COALESCE(req.fingerprint_amount, req.amount)::numeric, 2);
    v_received NUMERIC := ROUND(p_amount::numeric, 2);
  BEGIN
    IF v_received != v_expected THEN
      INSERT INTO security_audit_log(event_type, order_id, device_id, details)
      VALUES ('amount_mismatch_topup', p_order_id, p_device_id,
        jsonb_build_object('expected', v_expected, 'received', v_received));

      UPDATE wallet_topup_requests
      SET scan_status = 'amount_mismatch',
          failure_reason = 'المبلغ غير مطابق: مطلوب ' || v_expected || ' تم استلام ' || v_received,
          updated_at = now()
      WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');

      RETURN jsonb_build_object(
        'ok', false, 'reason', 'amount_mismatch',
        'expected', v_expected, 'received', v_received
      );
    END IF;
  END;

  -- ── Duplicate transaction_id check ──────────────────────────────────────
  IF p_transaction_id IS NOT NULL THEN
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions WHERE transaction_id = p_transaction_id
    ) THEN
      UPDATE wallet_topup_requests
      SET status = 'rejected', scan_status = 'rejected',
          failure_reason = 'رقم العملية مستخدم سابقاً - ' || p_transaction_id
      WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');
      RETURN jsonb_build_object('ok', false, 'reason', 'duplicate_transaction_id',
        'order_id', p_order_id);
    END IF;
  END IF;

  credits_to_add := COALESCE(req.credits_requested, req.amount);

  BEGIN
    INSERT INTO confirmed_transactions(
      transaction_id, order_id, user_id, sender_phone, sender_name,
      amount, receiver_wallet, transaction_time, status
    ) VALUES (
      p_transaction_id, p_order_id, req.customer_id,
      p_sender_phone, p_sender_name, p_amount,
      p_receiver_wallet, COALESCE(p_transaction_time, now()), 'confirmed'
    );
  EXCEPTION WHEN unique_violation THEN
    UPDATE wallet_topup_requests
    SET status = 'rejected', scan_status = 'rejected',
        failure_reason = 'رقم العملية مستخدم سابقاً - duplicate transaction_id'
    WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');
    RETURN jsonb_build_object('ok', false, 'reason', 'duplicate_transaction_id',
      'order_id', p_order_id);
  END;

  SELECT wallet_balance INTO current_balance FROM profiles WHERE id = req.customer_id;
  new_balance := COALESCE(current_balance, 0) + credits_to_add;

  UPDATE profiles SET wallet_balance = new_balance, updated_at = now()
  WHERE id = req.customer_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Profile not found for user %', req.customer_id;
  END IF;

  UPDATE wallet_topup_requests SET
    status = 'approved', scan_status = 'approved',
    processed_at = now(), confirmed_at = now(),
    matched_automatically = TRUE,
    transaction_id = p_transaction_id,
    sender_name = COALESCE(p_sender_name, sender_name),
    assigned_device_id = COALESCE(p_device_id, assigned_device_id),
    failure_reason = NULL, updated_at = now()
  WHERE id = p_order_id;

  INSERT INTO wallet_transactions(customer_id, type, amount, balance_before, balance_after, reason, reference)
  VALUES (req.customer_id, 'credit', credits_to_add, COALESCE(current_balance, 0), new_balance,
    'Vodafone Cash auto-confirmation', p_transaction_id);

  INSERT INTO notifications(user_id, type, title, body)
  VALUES (req.customer_id, 'wallet_topup', 'تم شحن رصيدك تلقائياً',
    'تمت إضافة ' || credits_to_add || ' Credit. رصيدك الآن: ' || new_balance || ' Credit.');

  IF p_device_id IS NOT NULL THEN
    UPDATE sms_device_status SET last_order_processed_at = now(), updated_at = now()
    WHERE device_id = p_device_id;
  END IF;

  RETURN jsonb_build_object(
    'ok', true, 'confirmed', true,
    'order_id', p_order_id, 'new_balance', new_balance,
    'transaction_id', p_transaction_id,
    'credits_added', credits_to_add
  );
END;
$function$;