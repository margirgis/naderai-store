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
      pt.id                   AS task_id,
      pt.request_id,
      wtr.order_number,
      pt.amount_requested,
      pt.sender_phone_requested,
      pt.sender_name_requested,
      pt.fingerprint_amount,
      pt.credits_amount,
      pt.retry_count,
      pt.assigned_at,
      pt.payment_order_id,
      pt.order_expires_at,
      wtr.notes
    FROM pending_tasks pt
    JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
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
$$;