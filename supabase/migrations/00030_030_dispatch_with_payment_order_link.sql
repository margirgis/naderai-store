
-- Update assign_task_to_device to carry payment_order_id + order_expires_at
-- so wallet-auto-confirm can confirm atomically when task result arrives.
CREATE OR REPLACE FUNCTION assign_task_to_device(
  p_request_id UUID,
  p_device_id  TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  req      wallet_topup_requests%ROWTYPE;
  task_id  UUID;
  pay_ord  UUID;
  exp_at   TIMESTAMPTZ;
BEGIN
  SELECT * INTO req FROM wallet_topup_requests
  WHERE id = p_request_id FOR UPDATE;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request not found');
  END IF;
  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request is not pending');
  END IF;

  -- Detect linked payment_order via notes field
  BEGIN
    IF req.notes LIKE 'payment_order_id:%' THEN
      pay_ord := (regexp_match(req.notes, 'payment_order_id:([0-9a-f\-]{36})'))[1]::UUID;
      SELECT expires_at INTO exp_at FROM payment_orders WHERE id = pay_ord;
    END IF;
  EXCEPTION WHEN others THEN
    pay_ord := NULL; exp_at := NULL;
  END;

  INSERT INTO pending_tasks (
    request_id, device_id, task_status,
    amount_requested, sender_phone_requested, sender_name_requested,
    fingerprint_amount, credits_amount,
    payment_order_id, order_expires_at
  ) VALUES (
    p_request_id,
    p_device_id,
    'assigned',
    COALESCE(req.fingerprint_amount, req.amount),
    req.sender_phone,
    req.sender_name,
    req.fingerprint_amount,
    req.credits_requested,
    pay_ord,
    exp_at
  )
  RETURNING id INTO task_id;

  UPDATE wallet_topup_requests
  SET status             = 'scanning',
      scan_status        = 'scanning',
      assigned_device_id = p_device_id,
      scanning_started_at = now()
  WHERE id = p_request_id;

  RETURN jsonb_build_object(
    'ok', true, 'task_id', task_id,
    'payment_order_id', pay_ord,
    'order_expires_at', exp_at
  );
END;
$$;

-- Also update get_device_pending_tasks to return payment_order_id + order_expires_at
CREATE OR REPLACE FUNCTION get_device_pending_tasks(p_device_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
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
      AND pt.task_status IN ('pending', 'assigned')
    ORDER BY pt.created_at
    LIMIT 10
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
