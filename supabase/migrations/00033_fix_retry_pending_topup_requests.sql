CREATE OR REPLACE FUNCTION public.retry_pending_topup_requests(p_device_id text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  req_rec wallet_topup_requests%ROWTYPE;
  dispatched_count INT := 0;
  reassigned_count INT := 0;
  result JSONB;
BEGIN
  -- ─── 1) إنهاء المهام الموكلة لأجهزة غير متصلة ───
  -- إذا كان جهاز أُسندت إليه مهمة متوقف عن إرسال heartbeat، نعتبرها فاشلة
  -- ونعيد الطلب إلى حالة pending ليتم توزيعه على جهاز متصل.
  WITH stale_tasks AS (
    SELECT pt.id AS task_id, pt.request_id
    FROM pending_tasks pt
    JOIN sms_device_status sds ON sds.device_id = pt.device_id
    WHERE pt.task_status IN ('pending', 'assigned', 'in_progress')
      AND sds.last_heartbeat_at < (now() - INTERVAL '3 minutes')
  ),
  completed_tasks AS (
    UPDATE pending_tasks
    SET task_status = 'completed',
        result_status = 'failure',
        failure_reason = 'الجهاز غير متصل - تم إعادة التوزيع',
        completed_at = now(),
        updated_at = now()
    WHERE id IN (SELECT task_id FROM stale_tasks)
    RETURNING id, request_id
  ),
  reset_requests AS (
    UPDATE wallet_topup_requests w
    SET status = 'pending',
        scan_status = 'pending',
        updated_at = now()
    FROM completed_tasks ct
    WHERE w.id = ct.request_id
      AND w.status NOT IN ('approved', 'rejected')
    RETURNING w.id
  )
  SELECT COUNT(*) INTO reassigned_count FROM completed_tasks;

  -- ─── 2) توزيع الطلبات المؤهلة على الجهاز الحالي ───
  -- تشمل حالتي pending و scanning لأن submit_payment_details يبدأ بـ pending
  -- وإذا فشل الـ trigger التلقائي يبقى pending ويحتاج لتوزيع يدوي.
  FOR req_rec IN
    SELECT w.*
    FROM wallet_topup_requests w
    WHERE w.status IN ('pending', 'scanning')
      AND NOT EXISTS (
        SELECT 1 FROM pending_tasks pt
        WHERE pt.request_id = w.id
          AND pt.task_status IN ('pending', 'assigned', 'in_progress')
      )
    ORDER BY w.created_at ASC
  LOOP
    -- نجبر التوزيع على الجهاز الذي أرسل heartbeat لضمان عدم إرسال الطلب لجهاز آخر
    result := assign_task_to_device(req_rec.id, p_device_id);
    IF (result->>'ok')::boolean THEN
      dispatched_count := dispatched_count + 1;
    END IF;
  END LOOP;

  RETURN jsonb_build_object(
    'ok', true,
    'dispatched', dispatched_count,
    'reassigned_from_offline', reassigned_count
  );
END;
$$;