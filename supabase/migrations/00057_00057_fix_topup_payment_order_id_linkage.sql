
-- ══════════════════════════════════════════════════════════════════════
-- ROOT CAUSE FIX:
-- wallet_topup_requests.payment_order_id is ALWAYS NULL because:
--   1. create-payment-order stores it in notes as text "payment_order_id:UUID"
--   2. assign_task_to_device reads notes→pending_tasks.payment_order_id but
--      NEVER sets wallet_topup_requests.payment_order_id
--   3. confirm_payment_order looks up v_topup WHERE payment_order_id=p_order_id
--      → always NULL → never updates wallet_topup_requests → website stuck
--
-- FIXES:
--   A. assign_task_to_device: backfill wallet_topup_requests.payment_order_id
--   B. confirm_payment_order: dual-lookup (payment_order_id OR notes contains UUID)
--   C. Backfill existing rows: set payment_order_id from notes field
-- ══════════════════════════════════════════════════════════════════════

-- ── FIX A: assign_task_to_device — backfill payment_order_id on topup row ──
CREATE OR REPLACE FUNCTION public.assign_task_to_device(
  p_request_id UUID,
  p_device_id  TEXT
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $fn$
DECLARE
  req      wallet_topup_requests%ROWTYPE;
  task_id  UUID;
  pay_ord  UUID;
  exp_at   TIMESTAMPTZ;
BEGIN
  SELECT * INTO req FROM wallet_topup_requests
  WHERE id = p_request_id FOR UPDATE;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request not found');
  END IF;
  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request is not pending');
  END IF;

  -- Detect linked payment_order:
  -- Priority 1: column already set
  -- Priority 2: embedded in notes field as "payment_order_id:UUID"
  IF req.payment_order_id IS NOT NULL THEN
    pay_ord := req.payment_order_id;
  ELSIF req.notes LIKE '%payment_order_id:%' THEN
    BEGIN
      pay_ord := (regexp_match(req.notes, 'payment_order_id:([0-9a-f\-]{36})'))[1]::UUID;
    EXCEPTION WHEN others THEN
      pay_ord := NULL;
    END;
  END IF;

  IF pay_ord IS NOT NULL THEN
    SELECT expires_at INTO exp_at FROM payment_orders WHERE id = pay_ord;
  END IF;

  INSERT INTO pending_tasks (
    request_id, device_id, task_status,
    amount_requested, sender_phone_requested, sender_name_requested,
    fingerprint_amount, credits_amount,
    payment_order_id, order_expires_at
  ) VALUES (
    p_request_id,
    p_device_id,
    'assigned',
    COALESCE(req.fingerprint_amount, req.amount),
    req.sender_phone,
    req.sender_name,
    req.fingerprint_amount,
    req.credits_requested,
    pay_ord,
    exp_at
  )
  RETURNING id INTO task_id;

  -- KEY FIX: also write payment_order_id back to wallet_topup_requests
  UPDATE wallet_topup_requests
  SET status              = 'scanning',
      scan_status         = 'scanning',
      assigned_device_id  = p_device_id,
      scanning_started_at = now(),
      payment_order_id    = COALESCE(payment_order_id, pay_ord)  -- backfill if missing
  WHERE id = p_request_id;

  RAISE NOTICE '[TASK_ASSIGNED] request=% device=% task=% payment_order=%',
    p_request_id, p_device_id, task_id, pay_ord;

  RETURN jsonb_build_object(
    'ok', true, 'task_id', task_id,
    'payment_order_id', pay_ord,
    'order_expires_at', exp_at
  );
END;
$fn$;


-- ── FIX B: confirm_payment_order — dual-lookup for wallet_topup_requests ──
CREATE OR REPLACE FUNCTION public.confirm_payment_order(
  p_order_id        uuid,
  p_transaction_id  text,
  p_received_amount numeric,
  p_sender_phone    text        DEFAULT NULL,
  p_sender_name     text        DEFAULT NULL,
  p_sms_timestamp   timestamptz DEFAULT NULL,
  p_device_id       text        DEFAULT NULL,
  p_scan_id         text        DEFAULT NULL,
  p_idempotency_key text        DEFAULT NULL,
  p_sms_body        text        DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $fn$
DECLARE
  v_order          payment_orders%ROWTYPE;
  v_topup          wallet_topup_requests%ROWTYPE;
  v_balance_before NUMERIC;
  v_received_phone TEXT;
  v_expected_phone TEXT;
  SMS_WINDOW       CONSTANT INTERVAL := INTERVAL '5 minutes';
  MAX_SMS_AGE      CONSTANT INTERVAL := INTERVAL '30 minutes';
BEGIN

  RAISE NOTICE '[TRANSACTION_CHECK] order=% tx=% device=% amount=%',
    p_order_id, p_transaction_id, p_device_id, p_received_amount;

  -- ── STEP 1: Fast-path idempotency ─────────────────────────────────────
  IF p_transaction_id IS NOT NULL
     AND p_transaction_id NOT LIKE 'DEVICE-%'
     AND p_transaction_id NOT LIKE 'SMS-%'
     AND p_transaction_id NOT LIKE 'AUTO-%'
  THEN
    -- Same tx, same order → idempotent retry
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions ct
      JOIN wallet_topup_requests wtr ON ct.order_id = wtr.id
      WHERE ct.transaction_id = p_transaction_id AND wtr.payment_order_id = p_order_id
    ) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] idempotent retry tx=% order=%',
        p_transaction_id, p_order_id;
      RETURN jsonb_build_object('ok',true,'idempotent',true,
        'reason','already_confirmed','order_id',p_order_id);
    END IF;
    -- Also check via notes field (legacy)
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions ct
      JOIN wallet_topup_requests wtr ON ct.order_id = wtr.id
      WHERE ct.transaction_id = p_transaction_id
        AND (wtr.notes LIKE '%payment_order_id:'||p_order_id::text||'%')
    ) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] idempotent (notes-match) tx=% order=%',
        p_transaction_id, p_order_id;
      RETURN jsonb_build_object('ok',true,'idempotent',true,
        'reason','already_confirmed','order_id',p_order_id);
    END IF;

    -- Same tx, different order → fraud
    IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE transaction_id=p_transaction_id) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] tx=% already used for another order',
        p_transaction_id;
      INSERT INTO security_audit_log(event_type,order_id,device_id,details)
      VALUES ('replay_transaction_id_cross_order',p_order_id,p_device_id,
        jsonb_build_object('transaction_id',p_transaction_id,'amount',p_received_amount));
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية مستخدم في طلب آخر: '||p_transaction_id,
        verification_status='no_match'
      WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate',
        'reason','duplicate_transaction_id_cross_order',
        'message','رقم العملية '||p_transaction_id||' تم استخدامه مسبقاً في طلب آخر');
    END IF;

    -- Check payment_transactions ledger (race guard)
    IF EXISTS (
      SELECT 1 FROM payment_transactions
      WHERE transaction_id=p_transaction_id AND status='accepted' AND order_id<>p_order_id
    ) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] tx=% in payment_transactions for different order',
        p_transaction_id;
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية مستخدم (ledger): '||p_transaction_id
      WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate',
        'reason','duplicate_transaction_id_ledger');
    END IF;
  END IF;

  -- ── STEP 2: Lock payment_order ─────────────────────────────────────────
  SELECT * INTO v_order FROM payment_orders WHERE id=p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE NOTICE '[ORDER_NOT_FOUND] order=%', p_order_id;
    RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','order_not_found');
  END IF;
  RAISE NOTICE '[ORDER_FOUND] order=% status=% amount=% expires=%',
    p_order_id, v_order.status, v_order.expected_amount, v_order.expires_at;

  -- ── DUAL LOOKUP: payment_order_id column OR notes field ───────────────
  -- Method 1: direct FK column
  SELECT * INTO v_topup
  FROM wallet_topup_requests
  WHERE payment_order_id = p_order_id
  LIMIT 1;

  -- Method 2: notes field (legacy — app stores "payment_order_id:UUID" in notes)
  IF v_topup.id IS NULL THEN
    SELECT * INTO v_topup
    FROM wallet_topup_requests
    WHERE notes LIKE '%payment_order_id:'||p_order_id::text||'%'
    LIMIT 1;
    IF v_topup.id IS NOT NULL THEN
      RAISE NOTICE '[ORDER_FOUND] topup found via notes field — backfilling payment_order_id=%',
        p_order_id;
      -- Backfill the column for future lookups
      UPDATE wallet_topup_requests
      SET payment_order_id = p_order_id
      WHERE id = v_topup.id;
    END IF;
  END IF;

  RAISE NOTICE '[ORDER_FOUND] topup_id=% topup_status=%',
    v_topup.id, v_topup.status;

  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id=v_order.user_id) THEN
    RETURN jsonb_build_object('ok',false,'reason','user_not_found');
  END IF;

  -- ── STEP 3: Expiry ─────────────────────────────────────────────────────
  IF v_order.expires_at <= now() AND v_order.status NOT IN ('reopened') THEN
    RAISE NOTICE '[ORDER_EXPIRED] order=% expires=%', p_order_id, v_order.expires_at;
    UPDATE payment_orders SET status='expired' WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET status='expired' WHERE id=v_topup.id;
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','order_expired');
  END IF;

  -- ── STEP 4: Terminal status ────────────────────────────────────────────
  IF v_order.status='confirmed' THEN
    RAISE NOTICE '[ORDER_ALREADY_CONFIRMED] order=%', p_order_id;
    RETURN jsonb_build_object('ok',true,'idempotent',true,
      'scan_status','confirmed','reason','already_confirmed');
  END IF;
  IF v_order.status IN ('cancelled','failed','expired','duplicate') THEN
    RAISE NOTICE '[ORDER_TERMINAL] order=% status=%', p_order_id, v_order.status;
    RETURN jsonb_build_object('ok',false,'scan_status',v_order.status,
      'reason','order_in_terminal_status');
  END IF;

  -- ── STEP 5: SMS timestamp ──────────────────────────────────────────────
  IF p_sms_timestamp IS NOT NULL THEN
    IF p_sms_timestamp < (v_order.created_at - SMS_WINDOW) THEN
      RAISE NOTICE '[SMS_TOO_OLD] sms=% created=%', p_sms_timestamp, v_order.created_at;
      UPDATE payment_orders SET status='failed',
        failure_reason='SMS أقدم من إنشاء الطلب' WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','sms_too_old');
    END IF;
    IF p_sms_timestamp < (now() - MAX_SMS_AGE) THEN
      UPDATE payment_orders SET status='failed',
        failure_reason='SMS منتهية الصلاحية' WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','sms_expired');
    END IF;
  END IF;

  -- ── STEP 6: Sender phone ───────────────────────────────────────────────
  v_expected_phone := normalize_egyptian_phone(COALESCE(v_order.sender_phone,''));
  v_received_phone := normalize_egyptian_phone(COALESCE(p_sender_phone,''));
  IF v_expected_phone<>'' AND v_received_phone<>'' AND v_received_phone<>v_expected_phone THEN
    RAISE NOTICE '[SENDER_PHONE_MISMATCH] expected=% got=%',
      v_expected_phone, v_received_phone;
    INSERT INTO security_audit_log(event_type,user_id,order_id,device_id,details)
    VALUES ('sender_phone_mismatch',v_order.user_id,p_order_id,p_device_id,
      jsonb_build_object('expected',v_order.sender_phone,'received',p_sender_phone));
    UPDATE payment_orders SET status='manual_review',verification_status='no_match',
      failure_reason='رقم المحول غير مطابق' WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET scan_status='manual_review',
        failure_reason='رقم المحول غير مطابق'
      WHERE id=v_topup.id;
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','manual_review',
      'reason','sender_phone_mismatch',
      'expected',v_order.sender_phone,'received',p_sender_phone);
  END IF;

  -- ── STEP 7: Amount match ───────────────────────────────────────────────
  IF ROUND(p_received_amount::numeric,2)<>ROUND(v_order.expected_amount::numeric,2) THEN
    RAISE NOTICE '[AMOUNT_MISMATCH] expected=% got=%',
      v_order.expected_amount, p_received_amount;
    INSERT INTO security_audit_log(event_type,user_id,order_id,device_id,details)
    VALUES ('amount_mismatch',v_order.user_id,p_order_id,p_device_id,
      jsonb_build_object('expected',v_order.expected_amount,'received',p_received_amount));
    UPDATE payment_orders SET status='amount_mismatch',
      failure_reason='مبلغ غير مطابق: مطلوب '||v_order.expected_amount
        ||' تم استلام '||p_received_amount
    WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET scan_status='manual_review',
        failure_reason='المبلغ غير مطابق: مطلوب '||v_order.expected_amount
          ||' تم استلام '||p_received_amount
      WHERE id=v_topup.id AND status NOT IN ('approved','rejected');
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','manual_review',
      'reason','amount_mismatch',
      'expected',v_order.expected_amount,'received',p_received_amount);
  END IF;

  -- ── STEP 8: ATOMIC reserve in payment_transactions ────────────────────
  IF p_transaction_id IS NOT NULL AND p_transaction_id<>'' THEN
    BEGIN
      INSERT INTO payment_transactions(transaction_id,order_id,sender_phone,sender_name,
        amount,status,device_id,sms_body,confirmed_at)
      VALUES (p_transaction_id,p_order_id,p_sender_phone,p_sender_name,
        p_received_amount,'accepted',p_device_id,p_sms_body,now());
      RAISE NOTICE '[TRANSACTION_RESERVED] tx=% locked in payment_transactions',
        p_transaction_id;
    EXCEPTION WHEN unique_violation THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] race tx=% rejected', p_transaction_id;
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية وُجد مسبقاً (race): '||p_transaction_id
      WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate',
        'reason','race_condition_duplicate');
    END;
  END IF;

  -- Secondary: sms_transaction_receipts
  IF p_transaction_id IS NOT NULL AND p_transaction_id<>'' THEN
    BEGIN
      INSERT INTO sms_transaction_receipts(transaction_id,sender_phone,sender_name,
        amount,sms_body,device_id,payment_order_id,status)
      VALUES (p_transaction_id,p_sender_phone,p_sender_name,p_received_amount,
        p_sms_body,p_device_id,p_order_id,'accepted');
    EXCEPTION WHEN unique_violation OR not_null_violation THEN NULL;
    END;
  END IF;

  -- ── STEP 9: Confirm payment_order + credit wallet ──────────────────────
  RAISE NOTICE '[PAYMENT_CONFIRMED] order=% tx=% credits=% user=%',
    p_order_id, p_transaction_id, v_order.credits_qty, v_order.user_id;

  UPDATE payment_orders
  SET status='confirmed', verified_at=now(), confirmed_at=now(),
      transaction_id=p_transaction_id, verification_status='completed',
      failure_reason=NULL
  WHERE id=p_order_id;

  SELECT COALESCE(wallet_balance,0) INTO v_balance_before
  FROM profiles WHERE id=v_order.user_id;

  UPDATE profiles
  SET wallet_balance = COALESCE(wallet_balance,0) + COALESCE(v_order.credits_qty,0)
  WHERE id=v_order.user_id;

  RAISE NOTICE '[CREDIT_ADDED] user=% credits=% before=% after=%',
    v_order.user_id, v_order.credits_qty,
    v_balance_before, v_balance_before + COALESCE(v_order.credits_qty,0);

  INSERT INTO wallet_transactions(customer_id,type,amount,
    balance_before,balance_after,reason,reference)
  VALUES (v_order.user_id,'credit',v_order.credits_qty,
    v_balance_before, v_balance_before+v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #'||v_order.order_number,
    'PAY-ORDER-'||p_order_id::text);

  -- ── STEP 10: Update wallet_topup_requests (THE KEY FIX) ───────────────
  IF v_topup.id IS NOT NULL THEN
    -- Confirm the topup row — this is what makes the WEBSITE update
    UPDATE wallet_topup_requests SET
      status               = 'approved',
      scan_status          = 'approved',
      verification_status  = 'completed',
      transaction_id       = p_transaction_id,
      payment_order_id     = p_order_id,          -- ensure FK is set
      sender_phone         = COALESCE(p_sender_phone, sender_phone),
      sender_name          = COALESCE(p_sender_name,  sender_name),
      matched_automatically= true,
      confirmed_at         = now(),
      processed_at         = now(),
      failure_reason       = NULL
    WHERE id = v_topup.id
      AND status NOT IN ('approved','rejected');

    GET DIAGNOSTICS v_balance_before = ROW_COUNT;  -- reuse var as counter
    RAISE NOTICE '[ORDER_UPDATED] topup_id=% rows_updated=% → status=approved scan_status=approved',
      v_topup.id, v_balance_before;
    -- Reset
    SELECT COALESCE(wallet_balance,0) INTO v_balance_before
    FROM profiles WHERE id=v_order.user_id;

    -- Insert confirmed_transactions record
    BEGIN
      INSERT INTO confirmed_transactions(transaction_id,order_id,user_id,
        sender_phone,sender_name,amount,status,confirmed_at)
      VALUES (
        COALESCE(p_transaction_id,'AUTO-'||p_order_id::text),
        v_topup.id,
        v_order.user_id,
        p_sender_phone, p_sender_name,
        p_received_amount, 'confirmed', now()
      );
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

  ELSE
    RAISE NOTICE '[ORDER_UPDATED] WARNING: no wallet_topup_request found for order=% (notes-based lookup also failed)',
      p_order_id;
  END IF;

  -- ── Audit logs ──────────────────────────────────────────────────────────
  INSERT INTO admin_audit_log(admin_id, action, target_user, target_ref,
    amount, balance_before, balance_after, reason, metadata)
  VALUES (
    NULL,
    'auto_confirm_payment_order',
    v_order.user_id,
    p_order_id::text,
    p_received_amount,
    v_balance_before - v_order.credits_qty,   -- before credit
    v_balance_before,                          -- after credit (current)
    'تأكيد تلقائي - طلب #'||v_order.order_number,
    jsonb_build_object(
      'transaction_id', p_transaction_id,
      'credits_added',  v_order.credits_qty,
      'device_id',      p_device_id,
      'topup_id',       v_topup.id
    )
  );

  INSERT INTO financial_audit_log(event_type,order_id,transaction_id,actor,amount,metadata)
  VALUES (
    'payment_confirmed', p_order_id, p_transaction_id,
    COALESCE('device:'||p_device_id,'system'),
    p_received_amount,
    jsonb_build_object(
      'credits_added',  v_order.credits_qty,
      'user_id',        v_order.user_id,
      'topup_id',       v_topup.id
    )
  );

  -- User notification
  INSERT INTO notifications(user_id,type,title,body,order_id)
  VALUES (
    v_order.user_id, 'wallet_topup',
    'تم تأكيد طلب الشحن ✅',
    'تم إضافة '||v_order.credits_qty||' Credit إلى محفظتك — طلب #'||v_order.order_number,
    NULL
  );

  -- Admin notification
  PERFORM create_admin_notification(
    p_title        := '✅ تم تأكيد دفعة',
    p_message      := 'طلب #'||v_order.order_number||' — '||p_received_amount||' جنيه'
                      ||COALESCE(' — '||p_sender_phone,'')
                      ||COALESCE(' tx:'||p_transaction_id,''),
    p_event_type   := 'payment_confirmed',
    p_reference_id := p_order_id::text,
    p_device_id    := p_device_id
  );

  RAISE NOTICE '[SMS_FOUND] SUCCESS order=% tx=% credits=% topup=%',
    p_order_id, p_transaction_id, v_order.credits_qty, v_topup.id;

  RETURN jsonb_build_object(
    'ok',           true,
    'scan_status',  'confirmed',
    'order_id',     p_order_id,
    'order_number', v_order.order_number,
    'credits_added',v_order.credits_qty,
    'transaction_id',p_transaction_id,
    'topup_id',     v_topup.id
  );
END;
$fn$;


-- ── FIX C: Backfill existing wallet_topup_requests.payment_order_id ────
-- For all rows where payment_order_id IS NULL but notes contains payment_order_id:UUID
UPDATE wallet_topup_requests
SET payment_order_id = (
  regexp_match(notes, 'payment_order_id:([0-9a-f\-]{36})')
)[1]::UUID
WHERE payment_order_id IS NULL
  AND notes LIKE '%payment_order_id:%'
  AND (regexp_match(notes, 'payment_order_id:([0-9a-f\-]{36})'))[1] IS NOT NULL;

-- Verify backfill
DO $$
DECLARE v_count INT;
BEGIN
  SELECT COUNT(*) INTO v_count
  FROM wallet_topup_requests WHERE payment_order_id IS NOT NULL;
  RAISE NOTICE '[BACKFILL] wallet_topup_requests rows with payment_order_id=%', v_count;
END $$;
