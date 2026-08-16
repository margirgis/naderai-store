
-- ═══════════════════════════════════════════════════════════════════════
-- MIGRATION: State Machine صحيح في complete_device_task
-- - يُضيف تاريخ التغييرات إلى order_status_history
-- - يمنع تحديث الطلبات ذات الحالات النهائية (approved/rejected)
-- - يُصحّح قيمة scan_status لكل نتيجة
-- ═══════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION public.complete_device_task(
  p_task_id UUID,
  p_status TEXT,
  p_result_data JSONB DEFAULT NULL,
  p_failure_reason TEXT DEFAULT NULL,
  p_idempotency_key TEXT DEFAULT NULL
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  task          RECORD;
  req           RECORD;
  txn_id        TEXT;
  confirm_result JSONB;
  new_scan_status TEXT;
BEGIN
  -- قفل المهمة بشكل حصري لمنع التنفيذ المتوازي
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'task_not_found');
  END IF;

  -- حماية idempotency: إذا اكتملت المهمة مسبقًا، أرجع نفس النتيجة
  IF task.task_status = 'completed' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'task_status', 'completed',
                              'result_status', task.result_status);
  END IF;

  -- الحصول على بيانات الطلب مع قفل
  SELECT * INTO req FROM wallet_topup_requests WHERE id = task.request_id FOR UPDATE;

  -- حماية الحالات النهائية: منع إعادة معالجة طلبات تمت أو رُفضت
  IF req.status IN ('approved', 'rejected') THEN
    -- اكتمل الطلب مسبقاً — نضع المهمة كمكتملة فقط
    UPDATE pending_tasks SET task_status = 'completed', completed_at = now(), updated_at = now()
    WHERE id = p_task_id;
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'order_already_terminal',
                              'order_status', req.status);
  END IF;

  txn_id := p_result_data->>'transaction_id';

  -- ══════════════════════════════════════════════
  -- حالة success: تأكيد الدفع وإضافة الرصيد
  -- ══════════════════════════════════════════════
  IF p_status = 'success' AND txn_id IS NOT NULL THEN
    confirm_result := atomic_confirm_topup(
      p_order_id        := task.request_id,
      p_transaction_id  := txn_id,
      p_sender_phone    := p_result_data->>'sender_phone',
      p_sender_name     := p_result_data->>'sender_name',
      p_amount          := (p_result_data->>'amount')::NUMERIC,
      p_receiver_wallet := p_result_data->>'receiver_wallet',
      p_device_id       := task.device_id
    );

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = CASE WHEN confirm_result->>'ok' = 'true' THEN 'success' ELSE 'failure' END,
      result_data   = p_result_data,
      failure_reason= CASE WHEN confirm_result->>'ok' = 'false' THEN confirm_result->>'reason' ELSE NULL END,
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    -- تسجيل في SMS logs
    INSERT INTO sms_logs_devices(task_id, device_id, request_id, sender_phone, sender_name,
      amount, transaction_id, sms_body, matched)
    VALUES (p_task_id, task.device_id, task.request_id,
      p_result_data->>'sender_phone', p_result_data->>'sender_name',
      (p_result_data->>'amount')::NUMERIC, txn_id,
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

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'pending', 'amount_mismatch',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'مبلغ غير مطابق'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'amount_mismatch', 'reason', COALESCE(p_failure_reason, 'amount_mismatch'));

  -- ══════════════════════════════════════════════
  -- حالة not_found: لم يُعثر على رسالة مطابقة
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
      status        = 'pending',
      scan_status   = 'not_found',
      failure_reason= COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'),
      updated_at    = now()
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'pending', 'not_found',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'not_found', 'reason', COALESCE(p_failure_reason, 'not_found'));

  -- ══════════════════════════════════════════════
  -- حالة failure: خطأ تقني أثناء الفحص
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
      status        = 'pending',
      scan_status   = 'failed',
      failure_reason= COALESCE(p_failure_reason, 'خطأ تقني في جهاز الفحص'),
      updated_at    = now()
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    PERFORM insert_order_status_history(
      task.request_id, req.status, 'pending', 'failed',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'خطأ تقني'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'failed', 'reason', COALESCE(p_failure_reason, 'failure'));
  END IF;
END;
$$;

-- ═══════════════════════════════════════════════════════════════════════
-- تحديث atomic_confirm_topup لتسجيل balance_before في wallet_transactions
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION public.atomic_confirm_topup(
  p_order_id UUID,
  p_transaction_id TEXT,
  p_sender_phone TEXT,
  p_sender_name TEXT,
  p_amount NUMERIC,
  p_receiver_wallet TEXT DEFAULT NULL,
  p_transaction_time TIMESTAMPTZ DEFAULT NULL,
  p_device_id TEXT DEFAULT NULL
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  req           RECORD;
  credits_to_add NUMERIC;
  current_balance NUMERIC;
  new_balance   NUMERIC;
BEGIN
  SELECT * INTO req FROM wallet_topup_requests WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  IF req.status = 'approved' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'reason', 'already_confirmed', 'order_id', p_order_id);
  END IF;

  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'invalid_order_status', 'current_status', req.status);
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
    RETURN jsonb_build_object('ok', false, 'reason', 'duplicate_transaction_id', 'order_id', p_order_id);
  END;

  -- الحصول على الرصيد الحالي قبل التحديث
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
    failure_reason = NULL,
    updated_at = now()
  WHERE id = p_order_id;

  -- تسجيل في wallet_transactions مع balance_before
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
$$;
