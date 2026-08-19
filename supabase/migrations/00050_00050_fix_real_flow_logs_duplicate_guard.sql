
-- ═══════════════════════════════════════════════════════════════════════
-- Migration 00050: Fix Real Flow — Duplicate Guard + Structured Logs
--
-- ROOT CAUSES IDENTIFIED:
-- 1. confirm_payment_order checks confirmed_transactions but race condition
--    exists: two concurrent calls can both pass the SELECT EXISTS check
--    before either INSERT completes → double credit possible.
--    FIX: Use INSERT ... ON CONFLICT (UNIQUE constraint) as the ONLY guard.
--
-- 2. wallet_topup_requests UPDATE uses notes LIKE '%order_id%' which is
--    unreliable. FIX: use payment_order_id column directly.
--
-- 3. No structured logs → impossible to diagnose. FIX: RAISE NOTICE at
--    every step with prefixed tags for Edge Function log visibility.
--
-- 4. wallet_topup_requests.payment_order_id not indexed properly.
-- ═══════════════════════════════════════════════════════════════════════

-- ── Ensure payment_order_id column indexed on wallet_topup_requests ───
CREATE INDEX IF NOT EXISTS wtr_payment_order_id_idx
  ON wallet_topup_requests(payment_order_id)
  WHERE payment_order_id IS NOT NULL;

-- ── Ensure confirmed_transactions has UNIQUE on transaction_id ─────────
-- (already exists from migration 00022 but verify)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'confirmed_transactions_txn_id_unique'
      AND conrelid = 'confirmed_transactions'::regclass
  ) THEN
    ALTER TABLE confirmed_transactions
      ADD CONSTRAINT confirmed_transactions_txn_id_unique UNIQUE (transaction_id);
  END IF;
END $$;

-- ── REPLACE confirm_payment_order with race-condition-safe version ─────
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
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  v_order          payment_orders%ROWTYPE;
  v_profile        profiles%ROWTYPE;
  v_balance_before NUMERIC;
  v_received_phone TEXT;
  v_expected_phone TEXT;
  v_topup_id       UUID;
  SMS_WINDOW       CONSTANT INTERVAL := INTERVAL '5 minutes';
  MAX_SMS_AGE      CONSTANT INTERVAL := INTERVAL '30 minutes';
BEGIN

  -- ══════════════════════════════════════════════════════════════════
  -- LOG TAG: TRANSACTION_CHECK
  -- ══════════════════════════════════════════════════════════════════
  RAISE NOTICE '[TRANSACTION_CHECK] order_id=% tx_id=% device=% amount=%',
    p_order_id, p_transaction_id, p_device_id, p_received_amount;

  -- ── STEP 1: Idempotency — confirmed_transactions (read-first, fast path)
  IF p_transaction_id IS NOT NULL
     AND p_transaction_id NOT LIKE 'DEVICE-%'
     AND p_transaction_id NOT LIKE 'SMS-%'
     AND p_transaction_id NOT LIKE 'AUTO-%'
  THEN
    -- Same tx, same order → idempotent retry
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions
      WHERE transaction_id = p_transaction_id AND order_id = p_order_id
    ) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] idempotent retry tx=% order=%',
        p_transaction_id, p_order_id;
      RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_confirmed');
    END IF;

    -- Same tx, DIFFERENT order → fraud/replay
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions
      WHERE transaction_id = p_transaction_id AND order_id != p_order_id
    ) THEN
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] tx=% used in different order — marking duplicate',
        p_transaction_id;
      INSERT INTO security_audit_log(event_type, order_id, device_id, details)
      VALUES ('replay_transaction_id_cross_order', p_order_id, p_device_id,
        jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));
      UPDATE payment_orders SET status = 'duplicate',
        verification_status = 'no_match',
        failure_reason = 'رقم العملية مستخدم في طلب آخر: ' || p_transaction_id
      WHERE id = p_order_id;
      RETURN jsonb_build_object(
        'ok', false, 'scan_status', 'duplicate',
        'reason', 'duplicate_transaction_id_cross_order',
        'message', 'رقم العملية ' || p_transaction_id || ' تم استخدامه مسبقاً في طلب آخر'
      );
    END IF;
  END IF;

  -- ── STEP 2: Lock the order row ──────────────────────────────────────
  SELECT * INTO v_order FROM payment_orders WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE NOTICE '[ORDER_NOT_FOUND] order_id=%', p_order_id;
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('invalid_order_id', p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed', 'reason', 'order_not_found');
  END IF;

  RAISE NOTICE '[ORDER_FOUND] order_id=% status=% amount=% expires=%',
    p_order_id, v_order.status, v_order.expected_amount, v_order.expires_at;

  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed', 'reason', 'user_not_found');
  END IF;

  -- ── STEP 3: Expiry check ────────────────────────────────────────────
  IF v_order.expires_at <= now() AND v_order.status NOT IN ('reopened') THEN
    RAISE NOTICE '[ORDER_EXPIRED] order_id=% expires=%', p_order_id, v_order.expires_at;
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_expired', 'order_id', p_order_id);
  END IF;

  -- ── STEP 4: Terminal status check ───────────────────────────────────
  IF v_order.status = 'confirmed' THEN
    RAISE NOTICE '[ORDER_ALREADY_CONFIRMED] order_id=%', p_order_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_confirmed');
  END IF;
  IF v_order.status IN ('cancelled', 'failed', 'expired', 'duplicate') THEN
    RAISE NOTICE '[ORDER_TERMINAL] order_id=% status=%', p_order_id, v_order.status;
    RETURN jsonb_build_object('ok', false, 'scan_status', v_order.status,
      'reason', 'order_in_terminal_status');
  END IF;

  -- ── STEP 5: SMS timestamp checks ────────────────────────────────────
  IF p_sms_timestamp IS NOT NULL THEN
    IF p_sms_timestamp < (v_order.created_at - SMS_WINDOW) THEN
      RAISE NOTICE '[SMS_TOO_OLD] sms=% order_created=%', p_sms_timestamp, v_order.created_at;
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

  -- ── STEP 6: Sender phone validation ────────────────────────────────
  v_expected_phone := normalize_egyptian_phone(COALESCE(v_order.sender_phone, ''));
  v_received_phone := normalize_egyptian_phone(COALESCE(p_sender_phone, ''));
  IF v_expected_phone <> '' AND v_received_phone <> '' AND v_received_phone <> v_expected_phone THEN
    RAISE NOTICE '[SENDER_PHONE_MISMATCH] expected=% got=%', v_expected_phone, v_received_phone;
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('sender_phone_mismatch', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('expected', v_order.sender_phone, 'received', p_sender_phone));
    UPDATE payment_orders SET status = 'manual_review',
      verification_status = 'no_match',
      failure_reason = 'رقم المحول غير مطابق'
    WHERE id = p_order_id;
    RETURN jsonb_build_object(
      'ok', false, 'scan_status', 'manual_review',
      'reason', 'sender_phone_mismatch',
      'expected', v_order.sender_phone, 'received', p_sender_phone
    );
  END IF;

  -- ── STEP 7: EXACT amount match ──────────────────────────────────────
  IF ROUND(p_received_amount::numeric, 2) != ROUND(v_order.expected_amount::numeric, 2) THEN
    RAISE NOTICE '[AMOUNT_MISMATCH] expected=% got=%', v_order.expected_amount, p_received_amount;
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('amount_mismatch', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('expected', v_order.expected_amount, 'received', p_received_amount,
        'diff', ABS(p_received_amount - v_order.expected_amount)));
    UPDATE payment_orders SET status = 'amount_mismatch',
      failure_reason = 'مبلغ غير مطابق: مطلوب ' || v_order.expected_amount || ' تم استلام ' || p_received_amount
    WHERE id = p_order_id;
    UPDATE wallet_topup_requests SET
      scan_status    = 'manual_review',
      failure_reason = 'المبلغ غير مطابق: مطلوب ' || v_order.expected_amount || ' تم استلام ' || p_received_amount
    WHERE payment_order_id = p_order_id AND status NOT IN ('approved', 'rejected');
    RETURN jsonb_build_object(
      'ok', false, 'scan_status', 'manual_review',
      'reason', 'amount_mismatch',
      'expected', v_order.expected_amount, 'received', p_received_amount
    );
  END IF;

  -- ── STEP 8: ATOMIC insert into confirmed_transactions ───────────────
  -- This is the REAL duplicate guard: UNIQUE(transaction_id) at DB level.
  -- Concurrent calls will block here — only one succeeds.
  IF p_transaction_id IS NOT NULL AND p_transaction_id <> '' THEN
    BEGIN
      INSERT INTO confirmed_transactions(
        transaction_id, order_id, user_id,
        sender_phone, sender_name,
        amount, device_id, confirmed_at, status
      ) VALUES (
        p_transaction_id, p_order_id, v_order.user_id,
        p_sender_phone, p_sender_name,
        p_received_amount, p_device_id, now(), 'confirmed'
      );
      RAISE NOTICE '[TRANSACTION_RESERVED] tx=% order=%', p_transaction_id, p_order_id;
    EXCEPTION WHEN unique_violation THEN
      -- Race condition: another concurrent call inserted first
      RAISE NOTICE '[TRANSACTION_ALREADY_USED] race condition — tx=% already in confirmed_transactions',
        p_transaction_id;
      UPDATE payment_orders SET status = 'duplicate',
        failure_reason = 'رقم العملية وُجد مسبقاً (race condition): ' || p_transaction_id
      WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'duplicate',
        'reason', 'race_condition_duplicate');
    END;
  END IF;

  -- Also reserve in payment_transactions (new ledger table)
  IF p_transaction_id IS NOT NULL AND p_transaction_id <> '' THEN
    INSERT INTO payment_transactions(
      transaction_id, order_id, sender_phone, sender_name,
      amount, status, device_id, sms_body, confirmed_at
    ) VALUES (
      p_transaction_id, p_order_id, p_sender_phone, p_sender_name,
      p_received_amount, 'accepted', p_device_id, p_sms_body, now()
    ) ON CONFLICT (transaction_id) DO NOTHING;
  END IF;

  -- Also reserve in sms_transaction_receipts (existing table)
  IF p_transaction_id IS NOT NULL AND p_transaction_id <> '' THEN
    INSERT INTO sms_transaction_receipts(
      transaction_id, sender_phone, sender_name, amount,
      sms_body, device_id, payment_order_id, status
    ) VALUES (
      p_transaction_id, p_sender_phone, p_sender_name, p_received_amount,
      p_sms_body, p_device_id, p_order_id, 'accepted'
    ) ON CONFLICT (transaction_id) DO NOTHING;
  END IF;

  -- ── STEP 9: Final guard: already confirmed (order level) ────────────
  IF EXISTS (SELECT 1 FROM confirmed_transactions WHERE order_id = p_order_id AND transaction_id != COALESCE(p_transaction_id,'__NO_MATCH__')) THEN
    RAISE NOTICE '[ORDER_ALREADY_CONFIRMED_VIA_OTHER_TX] order=%', p_order_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_in_registry');
  END IF;

  -- ── STEP 10: Confirm order + credit wallet ──────────────────────────
  RAISE NOTICE '[PAYMENT_CONFIRMED] Confirming order=% tx=% credits=% user=%',
    p_order_id, p_transaction_id, v_order.credits_qty, v_order.user_id;

  UPDATE payment_orders
  SET status              = 'confirmed',
      verified_at         = now(),
      confirmed_at        = now(),
      transaction_id      = p_transaction_id,
      verification_status = 'completed',
      failure_reason      = NULL
  WHERE id = p_order_id;

  SELECT wallet_balance INTO v_balance_before FROM profiles WHERE id = v_order.user_id;

  UPDATE profiles
  SET wallet_balance  = COALESCE(wallet_balance, 0)  + COALESCE(v_order.credits_qty, 0),
      credits_balance = COALESCE(credits_balance, 0) + COALESCE(v_order.credits_qty, 0)
  WHERE id = v_order.user_id;

  RAISE NOTICE '[CREDIT_ADDED] user=% credits=% balance_before=% balance_after=%',
    v_order.user_id, v_order.credits_qty, v_balance_before,
    v_balance_before + COALESCE(v_order.credits_qty, 0);

  -- wallet_transactions log
  INSERT INTO wallet_transactions(
    customer_id, type, amount, balance_before, balance_after, reason, reference
  ) VALUES (
    v_order.user_id, 'credit', v_order.credits_qty,
    v_balance_before, v_balance_before + v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #' || v_order.order_number,
    'PAY-ORDER-' || p_order_id::text
  );

  -- ── STEP 11: Update linked wallet_topup_request by payment_order_id ─
  -- FIX: use payment_order_id column (reliable), not notes LIKE
  UPDATE wallet_topup_requests
  SET status               = 'approved',
      scan_status          = 'approved',
      verification_status  = 'completed',
      transaction_id       = p_transaction_id,
      sender_phone         = COALESCE(p_sender_phone, sender_phone),
      sender_name          = COALESCE(p_sender_name, sender_name),
      matched_automatically = true,
      processed_at         = now()
  WHERE payment_order_id = p_order_id
    AND status NOT IN ('approved', 'rejected');

  -- Get updated topup_id for logging
  SELECT id INTO v_topup_id FROM wallet_topup_requests
  WHERE payment_order_id = p_order_id LIMIT 1;

  RAISE NOTICE '[ORDER_UPDATED] topup_request updated for order=% topup_id=%',
    p_order_id, v_topup_id;

  -- Audit log
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

  -- Financial audit log
  INSERT INTO financial_audit_log(event_type, order_id, transaction_id, actor, amount, metadata)
  VALUES (
    'payment_confirmed',
    p_order_id,
    p_transaction_id,
    COALESCE('device:' || p_device_id, 'system'),
    p_received_amount,
    jsonb_build_object(
      'credits_added', v_order.credits_qty,
      'user_id', v_order.user_id,
      'balance_before', v_balance_before,
      'balance_after', v_balance_before + v_order.credits_qty,
      'topup_id', v_topup_id
    )
  );

  -- User notification
  INSERT INTO notifications(user_id, type, title, body, order_id)
  VALUES (
    v_order.user_id, 'wallet_topup',
    'تم تأكيد طلب الشحن ✅',
    'تم إضافة ' || v_order.credits_qty || ' Credit إلى محفظتك — طلب #' || v_order.order_number,
    NULL
  );

  -- Admin notification
  PERFORM create_admin_notification(
    p_title        := '✅ تم تأكيد دفعة',
    p_message      := 'طلب #' || v_order.order_number || ' — ' || p_received_amount || ' جنيه — ' || COALESCE(p_sender_phone, '') || ' — ' || COALESCE(p_transaction_id, ''),
    p_event_type   := 'payment_confirmed',
    p_reference_id := p_order_id::text,
    p_device_id    := p_device_id
  );

  RAISE NOTICE '[SMS_FOUND] transaction_id=% confirmed for order=%', p_transaction_id, p_order_id;

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
$function$;

-- ── Structured logs in complete_device_task ─────────────────────────────────
CREATE OR REPLACE FUNCTION public.complete_device_task(
  p_task_id         UUID,
  p_status          TEXT,
  p_result_data     JSONB    DEFAULT NULL,
  p_failure_reason  TEXT     DEFAULT NULL,
  p_idempotency_key TEXT     DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  task              RECORD;
  req               RECORD;
  txn_id            TEXT;
  confirm_result    JSONB;
  new_scan_status   TEXT;
  v_payment_order_id UUID;
  v_amount          NUMERIC;
  v_sms_timestamp   TIMESTAMPTZ;
  v_status          TEXT := p_status;
BEGIN

  RAISE NOTICE '[TASK_RESULT] task_id=% status=% device=%',
    p_task_id, p_status, p_result_data->>'device_id';

  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE NOTICE '[TASK_NOT_FOUND] task_id=%', p_task_id;
    RETURN jsonb_build_object('ok', false, 'reason', 'task_not_found');
  END IF;

  IF task.task_status = 'completed' THEN
    RAISE NOTICE '[TASK_ALREADY_COMPLETED] task_id=%', p_task_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'task_status', 'completed', 'result_status', task.result_status);
  END IF;

  SELECT * INTO req FROM wallet_topup_requests WHERE id = task.request_id FOR UPDATE;

  IF req.status IN ('approved', 'rejected') THEN
    UPDATE pending_tasks SET task_status = 'completed', completed_at = now(), updated_at = now()
    WHERE id = p_task_id;
    RAISE NOTICE '[ORDER_ALREADY_TERMINAL] status=% task_id=%', req.status, p_task_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'reason', 'order_already_terminal', 'order_status', req.status);
  END IF;

  txn_id := p_result_data->>'transaction_id';
  v_payment_order_id := task.payment_order_id;
  v_amount := (p_result_data->>'amount')::NUMERIC;
  BEGIN
    v_sms_timestamp := (p_result_data->>'transaction_time')::TIMESTAMPTZ;
  EXCEPTION WHEN OTHERS THEN
    v_sms_timestamp := NULL;
  END;

  RAISE NOTICE '[SMS_SCAN_RESULT] task=% tx_id=% amount=% order_id=%',
    p_task_id, txn_id, v_amount, v_payment_order_id;

  -- Expiry check
  IF task.order_expires_at IS NOT NULL AND now() > task.order_expires_at THEN
    v_status := 'failure';
    p_failure_reason := COALESCE(p_failure_reason, 'انتهت صلاحية الطلب قبل وصول نتيجة الفحص');
    RAISE NOTICE '[ORDER_EXPIRED] task=% expires=%', p_task_id, task.order_expires_at;
  END IF;

  IF v_status = 'success' AND (txn_id IS NULL OR txn_id = '') THEN
    v_status := 'failure';
    p_failure_reason := COALESCE(p_failure_reason, 'رقم العملية مفقود في SMS');
    RAISE NOTICE '[SMS_FOUND_NO_TX_ID] task=%', p_task_id;
  END IF;

  IF v_status = 'success' AND txn_id IS NOT NULL THEN
    RAISE NOTICE '[SMS_FOUND] task=% tx=% amount=% — calling confirm_payment_order',
      p_task_id, txn_id, v_amount;

    IF v_payment_order_id IS NOT NULL THEN
      confirm_result := confirm_payment_order(
        p_order_id        := v_payment_order_id,
        p_transaction_id  := txn_id,
        p_received_amount := v_amount,
        p_sender_phone    := p_result_data->>'sender_phone',
        p_sender_name     := p_result_data->>'sender_name',
        p_sms_timestamp   := v_sms_timestamp,
        p_device_id       := task.device_id,
        p_scan_id         := p_task_id::text,
        p_sms_body        := p_result_data->>'sms_body',
        p_idempotency_key := p_idempotency_key
      );
    ELSE
      confirm_result := atomic_confirm_topup(
        p_order_id        := task.request_id,
        p_transaction_id  := txn_id,
        p_sender_phone    := p_result_data->>'sender_phone',
        p_sender_name     := p_result_data->>'sender_name',
        p_amount          := v_amount,
        p_receiver_wallet := p_result_data->>'receiver_wallet',
        p_device_id       := task.device_id
      );
    END IF;

    RAISE NOTICE '[CONFIRM_RESULT] task=% ok=% scan_status=% reason=%',
      p_task_id,
      confirm_result->>'ok',
      confirm_result->>'scan_status',
      confirm_result->>'reason';

    UPDATE pending_tasks SET
      task_status    = 'completed',
      result_status  = CASE WHEN (confirm_result->>'ok')::boolean THEN 'success' ELSE 'failure' END,
      result_data    = p_result_data,
      failure_reason = CASE WHEN NOT (confirm_result->>'ok')::boolean THEN confirm_result->>'reason' ELSE NULL END,
      completed_at   = now(), updated_at = now()
    WHERE id = p_task_id;

    INSERT INTO sms_logs_devices(task_id, device_id, request_id, sender_phone, sender_name,
      amount, transaction_id, sms_body, matched)
    VALUES (p_task_id, task.device_id, task.request_id,
      p_result_data->>'sender_phone', p_result_data->>'sender_name',
      v_amount, txn_id, p_result_data->>'sms_body',
      (confirm_result->>'ok')::BOOLEAN)
    ON CONFLICT (transaction_id) DO NOTHING;

    UPDATE sms_device_status SET last_sms_at = now(), updated_at = now()
    WHERE device_id = task.device_id;

    PERFORM insert_order_status_history(
      task.request_id, req.status,
      CASE WHEN (confirm_result->>'ok')::boolean THEN 'approved' ELSE 'rejected' END,
      CASE WHEN (confirm_result->>'ok')::boolean THEN 'approved' ELSE 'failed' END,
      NULL, task.device_id,
      CASE WHEN (confirm_result->>'ok')::boolean THEN 'تم التأكيد تلقائياً'
           ELSE confirm_result->>'reason' END,
      p_result_data
    );

    RETURN confirm_result;

  ELSIF v_status = 'amount_mismatch' THEN
    RAISE NOTICE '[AMOUNT_MISMATCH] task=% — %', p_task_id, p_failure_reason;
    UPDATE pending_tasks SET task_status='completed', result_status='amount_mismatch',
      failure_reason=COALESCE(p_failure_reason,'مبلغ غير مطابق'),
      completed_at=now(), updated_at=now() WHERE id=p_task_id;
    UPDATE wallet_topup_requests SET status='pending', scan_status='manual_review',
      failure_reason=COALESCE(p_failure_reason,'مبلغ غير مطابق'), updated_at=now()
    WHERE id=task.request_id AND status NOT IN ('approved','rejected');
    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET status='amount_mismatch', updated_at=now()
      WHERE id=v_payment_order_id AND status NOT IN ('confirmed','cancelled','expired','duplicate');
    END IF;
    RETURN jsonb_build_object('ok',true,'auto_approved',false,
      'scan_status','manual_review','reason',COALESCE(p_failure_reason,'amount_mismatch'));

  ELSIF v_status = 'not_found' THEN
    RAISE NOTICE '[SMS_NOT_FOUND] task=%', p_task_id;
    UPDATE pending_tasks SET task_status='completed', result_status='not_found',
      failure_reason=COALESCE(p_failure_reason,'لم يتم العثور على رسالة مطابقة'),
      completed_at=now(), updated_at=now() WHERE id=p_task_id;
    UPDATE wallet_topup_requests SET status='rejected', scan_status='rejected',
      failure_reason=COALESCE(p_failure_reason,'لم يتم العثور على رسالة مطابقة'), updated_at=now()
    WHERE id=task.request_id AND status NOT IN ('approved','rejected');
    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET status='cancelled', cancelled_at=now(), updated_at=now()
      WHERE id=v_payment_order_id AND status NOT IN ('confirmed','cancelled','expired','duplicate');
    END IF;
    RETURN jsonb_build_object('ok',true,'auto_approved',false,
      'scan_status','rejected','reason',COALESCE(p_failure_reason,'not_found'));

  ELSE
    RAISE NOTICE '[SCAN_FAILURE] task=% reason=%', p_task_id, p_failure_reason;
    UPDATE pending_tasks SET task_status='completed', result_status='failure',
      failure_reason=COALESCE(p_failure_reason,'خطأ تقني'),
      completed_at=now(), updated_at=now() WHERE id=p_task_id;
    UPDATE wallet_topup_requests SET status='rejected', scan_status='rejected',
      failure_reason=COALESCE(p_failure_reason,'خطأ تقني'), updated_at=now()
    WHERE id=task.request_id AND status NOT IN ('approved','rejected');
    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET status='failed', updated_at=now()
      WHERE id=v_payment_order_id AND status NOT IN ('confirmed','cancelled','expired','duplicate');
    END IF;
    RETURN jsonb_build_object('ok',true,'auto_approved',false,
      'scan_status','rejected','reason',COALESCE(p_failure_reason,'failure'));
  END IF;
END;
$$;
