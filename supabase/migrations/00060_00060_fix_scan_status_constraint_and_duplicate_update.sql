
-- Migration 00060: Add missing scan_status values + fix complete_device_task duplicate path
-- scan_status CHECK was missing: 'duplicate', 'amount_mismatch', 'waiting_for_verification'

ALTER TABLE public.wallet_topup_requests
  DROP CONSTRAINT IF EXISTS wallet_topup_requests_scan_status_check;

ALTER TABLE public.wallet_topup_requests
  ADD CONSTRAINT wallet_topup_requests_scan_status_check
  CHECK (scan_status = ANY (ARRAY[
    'pending','scanning','verified','approved','rejected',
    'manual_review','duplicate','amount_mismatch',
    'waiting_for_verification','confirmed'
  ]));

-- Fix order #2036 which is stuck on 'scanning' after duplicate rejection
UPDATE public.wallet_topup_requests
SET
  status         = 'rejected',
  scan_status    = 'duplicate',
  failure_reason = 'رقم العملية 022768543034 تم استخدامه مسبقاً في طلب آخر',
  updated_at     = now()
WHERE id = 'b529192d-937c-46d2-8a00-a92c7063136c'
  AND status NOT IN ('approved');

-- Fix complete_device_task: on duplicate_transaction_id_cross_order,
-- update wallet_topup_requests to rejected/duplicate (was being skipped)
CREATE OR REPLACE FUNCTION public.complete_device_task(
  p_task_id         UUID,
  p_status          TEXT,
  p_result_data     JSONB   DEFAULT NULL,
  p_failure_reason  TEXT    DEFAULT NULL,
  p_idempotency_key TEXT    DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
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

    -- ── FIX: If duplicate, explicitly update wallet_topup_requests ──────
    -- confirm_payment_order sets payment_orders.status=duplicate but may not
    -- update wallet_topup_requests when reason=duplicate_transaction_id_cross_order
    IF (confirm_result->>'ok')::boolean = false
       AND (confirm_result->>'reason') IN (
         'duplicate_transaction_id',
         'duplicate_transaction_id_cross_order'
       )
    THEN
      UPDATE wallet_topup_requests
      SET status         = 'rejected',
          scan_status    = 'duplicate',
          failure_reason = COALESCE(
            confirm_result->>'message',
            'رقم العملية مستخدم مسبقاً: ' || txn_id
          ),
          updated_at     = now()
      WHERE id = task.request_id
        AND status NOT IN ('approved', 'rejected');

      RAISE NOTICE '[DUPLICATE_MARKED] topup_request=% marked rejected/duplicate', task.request_id;
    END IF;

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
      CASE
        WHEN (confirm_result->>'ok')::boolean THEN 'approved'
        WHEN (confirm_result->>'reason') IN ('duplicate_transaction_id','duplicate_transaction_id_cross_order') THEN 'rejected'
        ELSE 'rejected'
      END,
      CASE
        WHEN (confirm_result->>'ok')::boolean THEN 'approved'
        WHEN (confirm_result->>'reason') IN ('duplicate_transaction_id','duplicate_transaction_id_cross_order') THEN 'duplicate'
        ELSE 'failed'
      END,
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
