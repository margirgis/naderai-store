-- Device task assignment and active SMS scanning

-- Extend wallet_topup_requests for device scanning workflow
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS assigned_device_id TEXT,
  ADD COLUMN IF NOT EXISTS scanning_started_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS scan_status TEXT DEFAULT 'pending' CHECK (scan_status IN ('pending', 'scanning', 'verified', 'approved', 'rejected', 'manual_review'));

-- Update existing rows to match current status
UPDATE wallet_topup_requests
SET scan_status = status
WHERE scan_status IS NULL;

-- Device task queue
CREATE TABLE IF NOT EXISTS pending_tasks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id uuid NOT NULL REFERENCES wallet_topup_requests(id) ON DELETE CASCADE,
  device_id TEXT,
  task_status TEXT NOT NULL DEFAULT 'pending' CHECK (task_status IN ('pending', 'assigned', 'in_progress', 'completed', 'failed')),
  amount_requested NUMERIC(12,2) NOT NULL,
  sender_phone_requested TEXT,
  sender_name_requested TEXT,
  fingerprint_amount NUMERIC(10,2),
  credits_amount NUMERIC(10,2),
  assigned_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  result_status TEXT CHECK (result_status IN ('success', 'failure', 'not_found')),
  result_data JSONB,
  failure_reason TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS pending_tasks_device_status_idx ON pending_tasks(device_id, task_status);
CREATE INDEX IF NOT EXISTS pending_tasks_request_idx ON pending_tasks(request_id);

ALTER TABLE pending_tasks ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "admin_all_pending_tasks" ON pending_tasks;
CREATE POLICY "admin_all_pending_tasks" ON pending_tasks
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'))
  WITH CHECK (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

DROP POLICY IF EXISTS "device_read_own_tasks" ON pending_tasks;
CREATE POLICY "device_read_own_tasks" ON pending_tasks
  FOR SELECT TO anon
  USING (device_id IS NOT NULL);

DROP POLICY IF EXISTS "device_update_own_tasks" ON pending_tasks;
CREATE POLICY "device_update_own_tasks" ON pending_tasks
  FOR UPDATE TO anon
  USING (device_id IS NOT NULL)
  WITH CHECK (device_id IS NOT NULL);

-- SMS logs from devices
CREATE TABLE IF NOT EXISTS sms_logs_devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id uuid REFERENCES pending_tasks(id) ON DELETE SET NULL,
  device_id TEXT,
  request_id uuid REFERENCES wallet_topup_requests(id) ON DELETE SET NULL,
  sender_phone TEXT,
  sender_name TEXT,
  amount NUMERIC(10,2),
  transaction_id TEXT,
  sms_body TEXT,
  matched BOOLEAN DEFAULT FALSE,
  scanned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS sms_logs_devices_request_idx ON sms_logs_devices(request_id);
CREATE INDEX IF NOT EXISTS sms_logs_devices_device_idx ON sms_logs_devices(device_id);

ALTER TABLE sms_logs_devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "admin_all_sms_logs_devices" ON sms_logs_devices;
CREATE POLICY "admin_all_sms_logs_devices" ON sms_logs_devices
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- Trigger: auto-update updated_at on pending_tasks
CREATE OR REPLACE FUNCTION set_pending_tasks_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS pending_tasks_updated_at ON pending_tasks;
CREATE TRIGGER pending_tasks_updated_at
  BEFORE UPDATE ON pending_tasks
  FOR EACH ROW EXECUTE FUNCTION set_pending_tasks_updated_at();

-- Function: assign a pending task to an online device
CREATE OR REPLACE FUNCTION assign_topup_task_to_device(p_request_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  req wallet_topup_requests%ROWTYPE;
  device sms_device_status%ROWTYPE;
  task_id UUID;
BEGIN
  SELECT * INTO req FROM wallet_topup_requests WHERE id = p_request_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request not found');
  END IF;

  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Request is not pending');
  END IF;

  -- Pick the most recently active online device
  SELECT * INTO device
  FROM sms_device_status
  WHERE status = 'online' AND is_active = true
  ORDER BY last_heartbeat_at DESC
  LIMIT 1;

  IF NOT FOUND THEN
    UPDATE wallet_topup_requests
      SET failure_reason = 'لا يوجد جهاز Android متصل'
    WHERE id = p_request_id;
    RETURN jsonb_build_object('ok', false, 'reason', 'No online device available');
  END IF;

  -- Create task
  INSERT INTO pending_tasks (
    request_id,
    device_id,
    task_status,
    amount_requested,
    sender_phone_requested,
    sender_name_requested,
    fingerprint_amount,
    credits_amount,
    assigned_at
  ) VALUES (
    req.id,
    device.device_id,
    'assigned',
    COALESCE(req.fingerprint_amount, req.amount),
    req.sender_phone,
    req.sender_name,
    req.fingerprint_amount,
    req.amount,
    now()
  ) RETURNING id INTO task_id;

  -- Update request
  UPDATE wallet_topup_requests
  SET
    status = 'scanning',
    scan_status = 'scanning',
    assigned_device_id = device.device_id,
    scanning_started_at = now(),
    updated_at = now()
  WHERE id = p_request_id;

  RETURN jsonb_build_object(
    'ok', true,
    'task_id', task_id,
    'device_id', device.device_id,
    'device_model', device.device_model
  );
END;
$$;

-- Function: fetch pending tasks for a device
CREATE OR REPLACE FUNCTION get_device_pending_tasks(p_device_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  result JSONB;
BEGIN
  SELECT jsonb_agg(
    jsonb_build_object(
      'task_id', t.id,
      'request_id', t.request_id,
      'amount_requested', t.amount_requested,
      'sender_phone_requested', t.sender_phone_requested,
      'sender_name_requested', t.sender_name_requested,
      'fingerprint_amount', t.fingerprint_amount,
      'credits_amount', t.credits_amount,
      'created_at', t.created_at
    )
  ) INTO result
  FROM pending_tasks t
  WHERE t.device_id = p_device_id
    AND t.task_status IN ('pending', 'assigned')
  ORDER BY t.created_at ASC;

  RETURN COALESCE(result, '[]'::jsonb);
END;
$$;

-- Function: mark task in_progress
CREATE OR REPLACE FUNCTION mark_task_in_progress(p_task_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  task pending_tasks%ROWTYPE;
BEGIN
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Task not found');
  END IF;

  UPDATE pending_tasks
  SET task_status = 'in_progress', updated_at = now()
  WHERE id = p_task_id;

  UPDATE wallet_topup_requests
  SET scan_status = 'scanning', updated_at = now()
  WHERE id = task.request_id AND status = 'scanning';

  RETURN jsonb_build_object('ok', true);
END;
$$;

-- Function: complete a task from device
CREATE OR REPLACE FUNCTION complete_device_task(
  p_task_id UUID,
  p_status TEXT,
  p_result_data JSONB DEFAULT NULL,
  p_failure_reason TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  task pending_tasks%ROWTYPE;
  req wallet_topup_requests%ROWTYPE;
  profile profiles%ROWTYPE;
  new_balance NUMERIC;
  norm_phone TEXT;
  sms_amount NUMERIC;
BEGIN
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Task not found');
  END IF;

  IF task.task_status = 'completed' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Task already completed');
  END IF;

  UPDATE pending_tasks
  SET
    task_status = 'completed',
    completed_at = now(),
    result_status = p_status,
    result_data = p_result_data,
    failure_reason = p_failure_reason,
    updated_at = now()
  WHERE id = p_task_id;

  IF p_status = 'success' AND p_result_data IS NOT NULL THEN
    SELECT * INTO req FROM wallet_topup_requests WHERE id = task.request_id;
    IF FOUND AND req.status = 'scanning' THEN
      norm_phone := normalize_egyptian_phone(COALESCE(p_result_data->>'sender_phone', task.sender_phone_requested));
      sms_amount := (p_result_data->>'amount')::NUMERIC;

      INSERT INTO sms_logs_devices (
        task_id, device_id, request_id, sender_phone, sender_name,
        amount, transaction_id, sms_body, matched
      ) VALUES (
        task.id,
        task.device_id,
        task.request_id,
        norm_phone,
        p_result_data->>'sender_name',
        sms_amount,
        p_result_data->>'transaction_id',
        p_result_data->>'sms_body',
        TRUE
      );

      IF sms_amount IS NOT NULL
         AND norm_phone IS NOT NULL
         AND task.fingerprint_amount IS NOT NULL
         AND ABS(task.fingerprint_amount - sms_amount) <= 0.01
         AND normalize_egyptian_phone(task.sender_phone_requested) = norm_phone
      THEN
        SELECT * INTO profile FROM profiles WHERE id = req.customer_id;
        IF FOUND THEN
          new_balance := COALESCE(profile.wallet_balance, 0) + req.amount;

          UPDATE profiles SET wallet_balance = new_balance WHERE id = req.customer_id;

          INSERT INTO wallet_transactions (customer_id, type, amount, balance_after, reason, reference)
          VALUES (
            req.customer_id,
            'credit',
            req.amount,
            new_balance,
            'Vodafone Cash auto-confirmation via device',
            COALESCE(p_result_data->>'transaction_id', 'DEVICE-' || EXTRACT(EPOCH FROM NOW())::TEXT)
          );

          UPDATE wallet_topup_requests
          SET
            status = 'approved',
            scan_status = 'approved',
            processed_at = now(),
            matched_automatically = TRUE,
            sender_name = COALESCE(p_result_data->>'sender_name', req.sender_name),
            transaction_id = COALESCE(p_result_data->>'transaction_id', req.transaction_id),
            notes = 'Auto-approved via device scan. Sender: ' || norm_phone
          WHERE id = req.id;

          INSERT INTO notifications (user_id, type, title, body)
          VALUES (
            req.customer_id,
            'wallet_topup',
            'تم شحن رصيدك تلقائياً',
            'تمت إضافة ' || req.amount || ' Credit إلى محفظتك. رصيدك الآن: ' || new_balance || ' Credit.'
          );

          RETURN jsonb_build_object(
            'ok', true,
            'request_id', req.id,
            'new_balance', new_balance,
            'auto_approved', true
          );
        END IF;
      END IF;

      UPDATE wallet_topup_requests
      SET
        status = 'pending',
        scan_status = 'manual_review',
        failure_reason = COALESCE(p_failure_reason, 'بيانات الرسالة لا تطابق طلب الشحن')
      WHERE id = req.id;

      RETURN jsonb_build_object(
        'ok', true,
        'request_id', req.id,
        'auto_approved', false,
        'reason', 'Message data did not match request'
      );
    END IF;
  END IF;

  UPDATE wallet_topup_requests
  SET
    status = 'pending',
    scan_status = 'manual_review',
    failure_reason = COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة')
  WHERE id = task.request_id AND status = 'scanning';

  RETURN jsonb_build_object('ok', true, 'auto_approved', false);
END;
$$;

-- Trigger wrapper to auto-assign task on new request
CREATE OR REPLACE FUNCTION auto_assign_topup_request_trigger()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  PERFORM assign_topup_task_to_device(NEW.id);
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS wallet_topup_request_auto_assign_task ON wallet_topup_requests;
CREATE TRIGGER wallet_topup_request_auto_assign_task
  AFTER INSERT ON wallet_topup_requests
  FOR EACH ROW
  EXECUTE FUNCTION auto_assign_topup_request_trigger();
