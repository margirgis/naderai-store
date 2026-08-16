-- Migration 032: complete_device_task now handles payment_orders and legacy topup paths
-- inside one atomic call, avoiding duplicate transaction_id conflicts.

-- Drop old 4-arg overload so there is only one authoritative signature.
DROP FUNCTION IF EXISTS public.complete_device_task(uuid, text, jsonb, text);

-- 5-arg authoritative function
CREATE OR REPLACE FUNCTION public.complete_device_task(
  p_task_id uuid,
  p_status text,
  p_result_data jsonb DEFAULT NULL::jsonb,
  p_failure_reason text DEFAULT NULL::text,
  p_idempotency_key text DEFAULT NULL::text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  task          RECORD;
  req           RECORD;
  txn_id        TEXT;
  confirm_result JSONB;
  new_scan_status TEXT;
  v_payment_order_id UUID;
  v_amount      NUMERIC;
  v_sms_timestamp TIMESTAMPTZ;
BEGIN
  -- قفل المهمة بشكل حصري
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'task_not_found');
  END IF;

  -- حماية idempotency
  IF task.task_status = 'completed' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'task_status', 'completed',
                              'result_status', task.result_status);
  END IF;

  -- الحصول على بيانات الطلب مع قفل
  SELECT * INTO req FROM wallet_topup_requests WHERE id = task.request_id FOR UPDATE;

  -- حماية الحالات النهائية
  IF req.status IN ('approved', 'rejected') THEN
    UPDATE pending_tasks SET task_status = 'completed', completed_at = now(), updated_at = now()
    WHERE id = p_task_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'order_already_terminal',
                              'order_status', req.status);
  END IF;

  txn_id := p_result_data->>'transaction_id';
  v_payment_order_id := task.payment_order_id;
  v_amount := (p_result_data->>'amount')::NUMERIC;
  BEGIN
    v_sms_timestamp := (p_result_data->>'transaction_time')::TIMESTAMPTZ;
  EXCEPTION WHEN OTHERS THEN
    v_sms_timestamp := NULL;
  END;

  -- ══════════════════════════════════════════════
  -- حالة success: تأكيد الدفع
  -- ══════════════════════════════════════════════
  IF p_status = 'success' AND txn_id IS NOT NULL THEN
    IF v_payment_order_id IS NOT NULL THEN
      -- مسار الطلبات المؤمنة (payment_orders)
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
      -- المسار القديم: wallet_topup_requests
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

    UPDATE pending_tasks SET
      task_status    = 'completed',
      result_status  = CASE WHEN confirm_result->>'ok' = 'true' THEN 'success' ELSE 'failure' END,
      result_data    = p_result_data,
      failure_reason = CASE WHEN confirm_result->>'ok' = 'false' THEN confirm_result->>'reason' ELSE NULL END,
      completed_at   = now(), updated_at = now()
    WHERE id = p_task_id;

    -- تسجيل في SMS logs
    INSERT INTO sms_logs_devices(task_id, device_id, request_id, sender_phone, sender_name,
      amount, transaction_id, sms_body, matched)
    VALUES (p_task_id, task.device_id, task.request_id,
      p_result_data->>'sender_phone', p_result_data->>'sender_name',
      v_amount, txn_id,
      p_result_data->>'sms_body', (confirm_result->>'ok')::BOOLEAN)
    ON CONFLICT (transaction_id) DO NOTHING;

    UPDATE sms_device_status SET last_sms_at = now(), updated_at = now()
    WHERE device_id = task.device_id;

    -- تسجيل تاريخ التغيير
    PERFORM insert_order_status_history(
      task.request_id, req.status,
      CASE WHEN confirm_result->>'ok' = 'true' THEN 'approved' ELSE 'rejected' END,
      CASE WHEN confirm_result->>'ok' = 'true' THEN 'approved' ELSE 'failed' END,
      NULL, task.device_id,
      CASE WHEN confirm_result->>'ok' = 'true' THEN 'تم التأكيد تلقائياً'
           ELSE confirm_result->>'reason' END,
      p_result_data
    );

    RETURN confirm_result;

  -- ══════════════════════════════════════════════
  -- حالة amount_mismatch: مبلغ غير مطابق
  -- ══════════════════════════════════════════════
  ELSIF p_status = 'amount_mismatch' THEN
    new_scan_status := 'amount_mismatch';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'amount_mismatch',
      failure_reason= COALESCE(p_failure_reason, 'مبلغ غير مطابق'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'pending',
      scan_status   = 'amount_mismatch',
      failure_reason= COALESCE(p_failure_reason, 'مبلغ غير مطابق'),
      updated_at    = now()
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET
        status = 'amount_mismatch',
        updated_at = now()
      WHERE id = v_payment_order_id AND status NOT IN ('confirmed', 'cancelled', 'expired', 'duplicate');
    END IF;

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'pending', 'amount_mismatch',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'مبلغ غير مطابق'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'amount_mismatch', 'reason', COALESCE(p_failure_reason, 'amount_mismatch'));

  -- ══════════════════════════════════════════════
  -- حالة not_found: لم يُعثر على رسالة مطابقة → رفض/إلغاء
  -- ══════════════════════════════════════════════
  ELSIF p_status = 'not_found' THEN
    new_scan_status := 'not_found';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'not_found',
      failure_reason= COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'rejected',
      scan_status   = 'not_found',
      failure_reason= COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'),
      updated_at    = now()
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET
        status = 'cancelled',
        cancelled_at = now(),
        updated_at = now()
      WHERE id = v_payment_order_id AND status NOT IN ('confirmed', 'cancelled', 'expired', 'duplicate');
    END IF;

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'rejected', 'not_found',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'not_found', 'reason', COALESCE(p_failure_reason, 'not_found'));

  -- ══════════════════════════════════════════════
  -- حالة failure: خطأ تقني
  -- ══════════════════════════════════════════════
  ELSE
    new_scan_status := 'failed';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'failure',
      failure_reason= COALESCE(p_failure_reason, 'خطأ تقني'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'rejected',
      scan_status   = 'failed',
      failure_reason= COALESCE(p_failure_reason, 'خطأ تقني في جهاز الفحص'),
      updated_at    = now()
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    IF v_payment_order_id IS NOT NULL THEN
      UPDATE payment_orders SET
        status = 'failed',
        updated_at = now()
      WHERE id = v_payment_order_id AND status NOT IN ('confirmed', 'cancelled', 'expired', 'duplicate');
    END IF;

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'rejected', 'failed',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'خطأ تقني'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'failed', 'reason', COALESCE(p_failure_reason, 'failure'));
  END IF;
END;
$function$;