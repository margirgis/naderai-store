DROP FUNCTION IF EXISTS public.retry_pending_topup_requests(p_device_id text);

CREATE OR REPLACE FUNCTION public.retry_pending_topup_requests(p_device_id text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  req_rec          wallet_topup_requests%ROWTYPE;
  po_rec           payment_orders%ROWTYPE;
  dispatched_count INT := 0;
  reassigned_count INT := 0;
  reopened_count   INT := 0;
  result           JSONB;
BEGIN
  -- 1) Cancel stale assignments for offline devices, but DO NOT mark as failure
  WITH stale_tasks AS (
    SELECT pt.id AS task_id, pt.request_id
    FROM pending_tasks pt
    JOIN sms_device_status sds ON sds.device_id = pt.device_id
    WHERE pt.task_status IN ('pending', 'assigned', 'in_progress')
      AND sds.last_heartbeat_at < (now() - INTERVAL '3 minutes')
  ),
  cancelled_tasks AS (
    UPDATE pending_tasks
    SET task_status = 'cancelled', updated_at = now()
    WHERE id IN (SELECT task_id FROM stale_tasks)
    RETURNING id, request_id
  )
  SELECT COUNT(*) INTO reassigned_count FROM cancelled_tasks;

  UPDATE wallet_topup_requests w
  SET status = 'pending', scan_status = 'pending', updated_at = now()
  FROM cancelled_tasks ct
  WHERE w.id = ct.request_id AND w.status NOT IN ('approved', 'rejected');

  -- 2) Reopen expired-but-unconfirmed payment_orders (within last 24h)
  FOR po_rec IN
    SELECT po.*
    FROM payment_orders po
    JOIN wallet_topup_requests wtr ON wtr.payment_order_id = po.id
    WHERE po.status = 'expired'
      AND po.expires_at <= now()
      AND po.expires_at >= (now() - INTERVAL '24 hours')
      AND wtr.status IN ('pending', 'scanning', 'manual_review')
      AND NOT EXISTS (
        SELECT 1 FROM pending_tasks pt
        WHERE pt.request_id = wtr.id
          AND pt.task_status IN ('pending', 'assigned', 'in_progress', 'scanning')
      )
    ORDER BY po.created_at ASC
  LOOP
    UPDATE payment_orders
    SET status = 'pending', expires_at = now() + INTERVAL '15 minutes', updated_at = now()
    WHERE id = po_rec.id;

    UPDATE wallet_topup_requests
    SET status = 'pending', scan_status = 'pending', updated_at = now()
    WHERE payment_order_id = po_rec.id AND status NOT IN ('approved', 'rejected');

    reopened_count := reopened_count + 1;
  END LOOP;

  -- 3) Dispatch all pending/scanned requests to the reconnecting device
  FOR req_rec IN
    SELECT w.*
    FROM wallet_topup_requests w
    WHERE w.status IN ('pending', 'scanning')
      AND NOT EXISTS (
        SELECT 1 FROM pending_tasks pt
        WHERE pt.request_id = w.id
          AND pt.task_status IN ('pending', 'assigned', 'in_progress', 'scanning')
      )
    ORDER BY w.created_at ASC
  LOOP
    result := assign_task_to_device(req_rec.id, p_device_id);
    IF (result->>'ok')::boolean THEN
      dispatched_count := dispatched_count + 1;
    END IF;
  END LOOP;

  RETURN jsonb_build_object(
    'ok', true,
    'dispatched', dispatched_count,
    'reassigned_from_offline', reassigned_count,
    'reopened_expired', reopened_count
  );
END;
$$;

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
      pt.id AS task_id,
      pt.request_id,
      wtr.order_number,
      pt.amount_requested AS amount,
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
      p.phone AS customer_phone,
      p.email AS customer_email,
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
    WHERE device_id = p_device_id AND status = 'pending'
    ORDER BY created_at
    LIMIT 5
  ) c;

  RETURN jsonb_build_object('tasks', v_tasks, 'commands', v_cmds);
END;
$$;