-- إزالة التريجر المكرّر لتجنّب إنشاء مهمتين لنفس الطلب
DROP TRIGGER IF EXISTS wallet_topup_request_auto_assign_task ON wallet_topup_requests;

-- تحديث دالة التوجيه التلقائي لتشمل حماية من المهمة المكررة
CREATE OR REPLACE FUNCTION auto_dispatch_topup_request()
RETURNS trigger AS $$
DECLARE
  v_device_id TEXT;
  v_result JSONB;
BEGIN
  -- فقط الطلبات الجديدة المعلقة
  IF NEW.status IS DISTINCT FROM 'pending' THEN
    RETURN NEW;
  END IF;

  -- لا نعيد إنشاء مهمة إذا كان لها مهمة نشطة بالفعل
  IF EXISTS (
    SELECT 1 FROM pending_tasks
    WHERE request_id = NEW.id
      AND task_status IN ('pending', 'assigned', 'in_progress', 'scanning')
  ) THEN
    RETURN NEW;
  END IF;

  -- أحدث جهاز متصل (heartbeat في آخر 90 ثانية)
  SELECT device_id INTO v_device_id
  FROM sms_device_status
  WHERE last_heartbeat_at >= (now() - INTERVAL '90 seconds')
    AND is_active = true
  ORDER BY last_heartbeat_at DESC
  LIMIT 1;

  IF v_device_id IS NOT NULL THEN
    v_result := assign_task_to_device(NEW.id, v_device_id);
    IF (v_result->>'ok')::boolean THEN
      PERFORM create_admin_notification(
        'طلب شحن أُرسل للجهاز تلقائياً 📱',
        'الطلب #' || COALESCE(NEW.order_number::text, NEW.id::text) || ' أُرسل للجهاز ' || substring(v_device_id from 1 for 12) || ' فور الإنشاء',
        'auto_dispatch',
        NEW.id::text,
        v_device_id
      );
    END IF;
  ELSE
    PERFORM create_admin_notification(
      'طلب شحن جديد في انتظار جهاز متصل ⏳',
      'الطلب #' || COALESCE(NEW.order_number::text, NEW.id::text) || ' بانتظار توصيله لأقرب جهاز متصل',
      'pending_no_device',
      NEW.id::text,
      NULL
    );
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- تحديث دالة جلب مهام الجهاز ببيانات إضافية للتطبيق
CREATE OR REPLACE FUNCTION get_device_pending_tasks(p_device_id text)
RETURNS jsonb AS $$
DECLARE
  v_tasks JSONB;
  v_cmds  JSONB;
BEGIN
  SELECT COALESCE(jsonb_agg(t), '[]'::jsonb) INTO v_tasks
  FROM (
    SELECT
      pt.id                                     AS task_id,
      pt.request_id,
      wtr.order_number,
      pt.amount_requested                       AS amount,
      pt.amount_requested,
      pt.sender_phone_requested,
      pt.sender_name_requested,
      pt.fingerprint_amount,
      pt.credits_amount,
      wtr.credits_requested,
      pt.retry_count,
      pt.assigned_at,
      pt.payment_order_id,
      pt.order_expires_at,
      wtr.payment_method,
      p.phone                                   AS customer_phone,
      p.email                                   AS customer_email,
      wtr.notes
    FROM pending_tasks pt
    JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
    LEFT JOIN profiles p ON p.id = wtr.customer_id
    WHERE pt.device_id = p_device_id
      AND pt.task_status IN ('pending', 'assigned', 'scanning')
    ORDER BY pt.created_at
    LIMIT 20
  ) t;

  SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) INTO v_cmds
  FROM (
    SELECT id AS command_id, command_type, payload, created_at
    FROM device_commands
    WHERE device_id = p_device_id
      AND status = 'pending'
    ORDER BY created_at
    LIMIT 5
  ) c;

  RETURN jsonb_build_object('tasks', v_tasks, 'commands', v_cmds);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;