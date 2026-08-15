
-- Fix get_device_pending_tasks to return full request details for Android display
CREATE OR REPLACE FUNCTION get_device_pending_tasks(p_device_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_tasks JSONB;
  v_commands JSONB;
BEGIN
  SELECT jsonb_agg(jsonb_build_object(
    'task_id',               pt.id,
    'request_id',            pt.request_id,
    'amount_requested',      pt.amount_requested,
    'sender_phone_requested',pt.sender_phone_requested,
    'sender_name_requested', pt.sender_name_requested,
    'fingerprint_amount',    pt.fingerprint_amount,
    'credits_amount',        pt.credits_amount,
    'task_status',           pt.task_status,
    'created_at',            pt.created_at,
    -- Full request details for Android display
    'order_number',          wtr.order_number,
    'credits_requested',     wtr.credits_requested,
    'customer_email',        p.email,
    'customer_phone',        p.phone,
    'payment_method',        wtr.payment_method,
    'request_created_at',    wtr.created_at
  ) ORDER BY pt.created_at ASC)
  INTO v_tasks
  FROM pending_tasks pt
  LEFT JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
  LEFT JOIN profiles p ON p.id = wtr.customer_id
  WHERE pt.device_id = p_device_id
    AND pt.task_status IN ('pending', 'assigned', 'in_progress');

  SELECT get_pending_device_commands(p_device_id) INTO v_commands;

  RETURN jsonb_build_object(
    'tasks',    COALESCE(v_tasks, '[]'::jsonb),
    'commands', COALESCE(v_commands, '[]'::jsonb)
  );
END;
$$;
