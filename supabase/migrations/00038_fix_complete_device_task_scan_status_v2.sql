-- تصليح قيم scan_status غير المسموح بها في wallet_topup_requests

-- حذف وإعادة إنشاء complete_device_task
DROP FUNCTION IF EXISTS complete_device_task(uuid, text, jsonb, text, text);

CREATE OR REPLACE FUNCTION complete_device_task(
  p_task_id UUID,
  p_status TEXT,
  p_result_data JSONB DEFAULT NULL,
  p_failure_reason TEXT DEFAULT NULL,
  p_idempotency_key TEXT DEFAULT NULL
) RETURNS JSONB AS $$
DECLARE
  task          RECORD;
  req           RECORD;
  txn_id        TEXT;
  confirm_result JSONB;
  new_scan_status TEXT;
  v_payment_order_id UUID;
  v_amount      NUMERIC;
  v_sms_timestamp TIMESTAMPTZ;
  v_status      TEXT := p_status;
BEGIN
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'task_not_found');
  END IF;

  IF task.task_status = 'completed' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'task_status', 'completed',
                              'result_status', task.result_status);
  END IF;

  SELECT * INTO req FROM wallet_topup_requests WHERE id = task.request_id FOR UPDATE;

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

  IF task.order_expires_at IS NOT NULL AND now() > task.order_expires_at THEN
    v_status := 'failure';
    p_failure_reason := COALESCE(p_failure_reason, 'انتهت صلاحية الطلب قبل وصول نتيجة الفحص');
  END IF;

  IF v_status = 'success' AND (txn_id IS NULL OR txn_id = '') THEN
    v_status := 'failure';
    p_failure_reason := COALESCE(p_failure_reason, 'رقم العملية مفقود في SMS');
  END IF;

  IF v_status = 'success' AND txn_id IS NOT NULL THEN
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

    UPDATE pending_tasks SET
      task_status    = 'completed',
      result_status  = CASE WHEN confirm_result->>'ok' = 'true' THEN 'success' ELSE 'failure' END,
      result_data    = p_result_data,
      failure_reason = CASE WHEN confirm_result->>'ok' = 'false' THEN confirm_result->>'reason' ELSE NULL END,
      completed_at   = now(), updated_at = now()
    WHERE id = p_task_id;

    INSERT INTO sms_logs_devices(task_id, device_id, request_id, sender_phone, sender_name,
      amount, transaction_id, sms_body, matched)
    VALUES (p_task_id, task.device_id, task.request_id,
      p_result_data->>'sender_phone', p_result_data->>'sender_name',
      v_amount, txn_id,
      p_result_data->>'sms_body', (confirm_result->>'ok')::BOOLEAN)
    ON CONFLICT (transaction_id) DO NOTHING;

    UPDATE sms_device_status SET last_sms_at = now(), updated_at = now()
    WHERE device_id = task.device_id;

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

  ELSIF v_status = 'amount_mismatch' THEN
    new_scan_status := 'manual_review';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'amount_mismatch',
      failure_reason= COALESCE(p_failure_reason, 'مبلغ غير مطابق'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'pending',
      scan_status   = 'manual_review',
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
      task.request_id, req.status, 'pending', 'manual_review',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'مبلغ غير مطابق'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'manual_review', 'reason', COALESCE(p_failure_reason, 'amount_mismatch'));

  ELSIF v_status = 'not_found' THEN
    new_scan_status := 'rejected';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'not_found',
      failure_reason= COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'rejected',
      scan_status   = 'rejected',
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
      task.request_id, req.status, 'rejected', 'rejected',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'rejected', 'reason', COALESCE(p_failure_reason, 'not_found'));

  ELSE
    new_scan_status := 'rejected';

    UPDATE pending_tasks SET
      task_status   = 'completed',
      result_status = 'failure',
      failure_reason= COALESCE(p_failure_reason, 'خطأ تقني'),
      completed_at  = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status        = 'rejected',
      scan_status   = 'rejected',
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
      task.request_id, req.status, 'rejected', 'rejected',
      NULL, task.device_id,
      COALESCE(p_failure_reason, 'خطأ تقني'), NULL
    );

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'scan_status', 'rejected', 'reason', COALESCE(p_failure_reason, 'failure'));
  END IF;
END;
$$ LANGUAGE plpgsql;

-- حذف وإعادة إنشاء confirm_payment_order بالترتيب الصحيح للمعاملات
DROP FUNCTION IF EXISTS confirm_payment_order(uuid, text, numeric, text, text, timestamp with time zone, text, text, text, text);

CREATE OR REPLACE FUNCTION confirm_payment_order(
  p_order_id UUID,
  p_transaction_id TEXT,
  p_received_amount NUMERIC,
  p_sender_phone TEXT DEFAULT NULL,
  p_sender_name TEXT DEFAULT NULL,
  p_sms_timestamp TIMESTAMPTZ DEFAULT NULL,
  p_device_id TEXT DEFAULT NULL,
  p_scan_id TEXT DEFAULT NULL,
  p_idempotency_key TEXT DEFAULT NULL,
  p_sms_body TEXT DEFAULT NULL
) RETURNS JSONB AS $$
DECLARE
  v_order        payment_orders%ROWTYPE;
  v_profile      profiles%ROWTYPE;
  v_balance_before NUMERIC;
  SMS_WINDOW     CONSTANT INTERVAL := INTERVAL '5 minutes';
  MAX_SMS_AGE    CONSTANT INTERVAL := INTERVAL '20 minutes';
BEGIN
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

  SELECT * INTO v_order FROM payment_orders
  WHERE id = p_order_id FOR UPDATE;

  IF NOT FOUND THEN
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('invalid_order_id', p_order_id, p_device_id,
      jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_not_found');
  END IF;

  SELECT * INTO v_profile FROM profiles WHERE id = v_order.user_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'user_not_found');
  END IF;

  IF v_order.expires_at <= now() THEN
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
      'reason', 'order_expired', 'order_id', p_order_id);
  END IF;

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

  IF p_transaction_id IS NOT NULL AND p_transaction_id NOT LIKE 'DEVICE-%' AND p_transaction_id NOT LIKE 'SMS-%' THEN
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions WHERE transaction_id = p_transaction_id
    ) THEN
      INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
      VALUES ('replay_transaction_id', v_order.user_id, p_order_id, p_device_id,
        jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_received_amount));

      UPDATE payment_orders SET status = 'duplicate' WHERE id = p_order_id;

      UPDATE wallet_topup_requests
      SET scan_status = 'rejected',
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

  IF p_sms_timestamp IS NOT NULL THEN
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
    IF p_sms_timestamp < (now() - MAX_SMS_AGE) THEN
      UPDATE payment_orders SET status = 'failed' WHERE id = p_order_id;
      RETURN jsonb_build_object('ok', false, 'scan_status', 'failed',
        'reason', 'sms_expired',
        'message', 'الرسالة منتهية الصلاحية.');
    END IF;
  END IF;

  IF ABS(p_received_amount - v_order.expected_amount) > 0.01 THEN
    INSERT INTO security_audit_log(event_type, user_id, order_id, device_id, details)
    VALUES ('amount_mismatch', v_order.user_id, p_order_id, p_device_id,
      jsonb_build_object('expected', v_order.expected_amount, 'received', p_received_amount));

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

  IF EXISTS (
    SELECT 1 FROM confirmed_transactions WHERE order_id = p_order_id
  ) THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'scan_status', 'confirmed', 'reason', 'already_in_registry');
  END IF;

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
    v_order.user_id,
    'credit',
    v_order.credits_qty,
    v_balance_before,
    v_balance_before + v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #' || v_order.order_number,
    'PAY-ORDER-' || p_order_id::text
  );

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

  INSERT INTO notifications(
    user_id, type, title, body, order_id
  ) VALUES (
    v_order.user_id,
    'wallet_topup',
    'تم تأكيد طلب الشحن ✅',
    'تم إضافة ' || v_order.credits_qty || ' Credit إلى محفظتك — طلب #' || v_order.order_number,
    NULL
  );

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
$$ LANGUAGE plpgsql;

-- تحديث get_all_orders_for_admin لإرجاع result_status وسبب فشل المهمة
DROP FUNCTION IF EXISTS get_all_orders_for_admin();

CREATE OR REPLACE FUNCTION get_all_orders_for_admin()
RETURNS JSONB AS $$
DECLARE
  v_orders JSONB;
BEGIN
  SELECT COALESCE(jsonb_agg(o), '[]'::jsonb) INTO v_orders
  FROM (
    SELECT
      wtr.id                         AS request_id,
      wtr.order_number,
      po.id                          AS payment_order_id,
      po.order_number                AS payment_order_number,
      wtr.status,
      wtr.scan_status,
      COALESCE(wtr.fingerprint_amount, wtr.amount) AS amount_requested,
      wtr.credits_requested,
      wtr.sender_phone,
      wtr.sender_name,
      wtr.payment_method,
      wtr.assigned_device_id,
      wtr.created_at,
      wtr.updated_at,
      wtr.confirmed_at,
      wtr.failure_reason,
      wtr.transaction_id,
      p.email                        AS customer_email,
      p.phone                        AS customer_phone,
      po.expires_at                  AS order_expires_at,
      pt.id                          AS task_id,
      pt.task_status,
      pt.result_status,
      pt.failure_reason              AS task_failure_reason
    FROM wallet_topup_requests wtr
    LEFT JOIN payment_orders po ON po.id = wtr.payment_order_id
    LEFT JOIN profiles p ON p.id = wtr.customer_id
    LEFT JOIN LATERAL (
      SELECT id, task_status, result_status, failure_reason FROM pending_tasks
      WHERE request_id = wtr.id
        AND task_status IN ('pending', 'assigned', 'in_progress', 'scanning')
      ORDER BY created_at DESC
      LIMIT 1
    ) pt ON true
    ORDER BY wtr.created_at DESC
    LIMIT 500
  ) o;

  RETURN jsonb_build_object('ok', true, 'orders', v_orders);
END;
$$ LANGUAGE plpgsql;
