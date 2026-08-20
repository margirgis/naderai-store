
-- ═══════════════════════════════════════════════════════════════════════
-- Migration 00070: إصلاح get_device_pending_tasks
-- المشكلة: الدالة لا ترجع transaction_id, scan_status, status, failure_reason
--          فالأندرويد لا يستطيع عرض رقم العملية ووقت الرسالة بعد الـ sync
-- الإصلاح: إضافة الحقول المفقودة + status و scan_status من wallet_topup_requests
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION public.get_device_pending_tasks(p_device_id text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_tasks JSONB;
  v_cmds  JSONB;
BEGIN
  SELECT COALESCE(jsonb_agg(t), '[]'::jsonb) INTO v_tasks
  FROM (
    SELECT
      pt.id                        AS task_id,
      pt.request_id,
      wtr.order_number,
      -- payment_order_number من payment_orders إذا موجود
      po.order_number              AS payment_order_number,
      pt.amount_requested          AS amount,
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
      -- حالة الطلب الكاملة
      wtr.status,
      wtr.scan_status,
      wtr.failure_reason,
      -- رقم العملية ووقت الفحص (مهمان لعرضهما في الكارد)
      wtr.transaction_id,
      wtr.scanning_started_at      AS scanned_at,
      wtr.sender_phone             AS sender_phone_found,
      wtr.sender_name              AS sender_name_found,
      -- result من pending_tasks
      pt.result_status,
      pt.failure_reason            AS task_failure_reason,
      p.phone                      AS customer_phone,
      p.email                      AS customer_email,
      COALESCE(p.full_name, split_part(p.email, '@', 1)) AS customer_name,
      wtr.created_at               AS request_created_at,
      wtr.notes
    FROM pending_tasks pt
    JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
    LEFT JOIN profiles p ON p.id = wtr.customer_id
    LEFT JOIN payment_orders po ON po.id = pt.payment_order_id
    WHERE pt.device_id = p_device_id
      AND pt.task_status IN ('pending', 'assigned', 'scanning')
    ORDER BY pt.created_at
    LIMIT 20
  ) t;

  SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) INTO v_cmds
  FROM (
    SELECT
      id AS command_id,
      command_type,
      COALESCE(response_data, '{}'::jsonb) AS payload,
      created_at
    FROM device_commands
    WHERE device_id = p_device_id AND status = 'pending'
    ORDER BY created_at
    LIMIT 5
  ) c;

  RETURN jsonb_build_object(
    'pending_tasks', v_tasks,
    'commands', v_cmds
  );
END;
$$;
