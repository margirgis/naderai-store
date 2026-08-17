
-- Migration 022: Transaction dedup, order states, test ping

-- 1. transaction_id column + unique index on sms_logs_devices
ALTER TABLE sms_logs_devices
  ADD COLUMN IF NOT EXISTS transaction_id TEXT;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes
    WHERE tablename='sms_logs_devices' AND indexname='sms_logs_devices_txn_id_unique'
  ) THEN
    CREATE UNIQUE INDEX sms_logs_devices_txn_id_unique
      ON sms_logs_devices(transaction_id)
      WHERE transaction_id IS NOT NULL;
  END IF;
END $$;

-- 2. Extra columns on wallet_topup_requests
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS idempotency_key TEXT,
  ADD COLUMN IF NOT EXISTS duplicate_of uuid REFERENCES wallet_topup_requests(id);

-- 3. Device status extras
ALTER TABLE sms_device_status
  ADD COLUMN IF NOT EXISTS response_time_ms INTEGER,
  ADD COLUMN IF NOT EXISTS last_test_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_test_result TEXT,
  ADD COLUMN IF NOT EXISTS capabilities JSONB DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS last_sms_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_order_processed_at TIMESTAMPTZ;

-- 4. Confirmed transactions ledger
CREATE TABLE IF NOT EXISTS confirmed_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id TEXT NOT NULL,
  order_id uuid NOT NULL REFERENCES wallet_topup_requests(id),
  user_id uuid NOT NULL,
  sender_phone TEXT,
  sender_name TEXT,
  amount NUMERIC(12,2) NOT NULL,
  receiver_wallet TEXT,
  transaction_time TIMESTAMPTZ,
  status TEXT NOT NULL DEFAULT 'confirmed',
  confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT confirmed_transactions_txn_id_unique UNIQUE (transaction_id)
);

CREATE INDEX IF NOT EXISTS confirmed_transactions_order_idx ON confirmed_transactions(order_id);
CREATE INDEX IF NOT EXISTS confirmed_transactions_user_idx ON confirmed_transactions(user_id);

ALTER TABLE confirmed_transactions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "admin_all_confirmed_transactions" ON confirmed_transactions;
CREATE POLICY "admin_all_confirmed_transactions" ON confirmed_transactions
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'))
  WITH CHECK (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- 5. Notifications table
CREATE TABLE IF NOT EXISTS notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE,
  type TEXT NOT NULL DEFAULT 'info',
  title TEXT,
  body TEXT,
  is_read BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS notifications_user_idx ON notifications(user_id, is_read);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "own_notifications" ON notifications;
CREATE POLICY "own_notifications" ON notifications
  FOR ALL TO authenticated
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "admin_all_notifications" ON notifications;
CREATE POLICY "admin_all_notifications" ON notifications
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'))
  WITH CHECK (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- 6. Atomic confirm with dedup protection
CREATE OR REPLACE FUNCTION atomic_confirm_topup(
  p_order_id uuid,
  p_transaction_id TEXT,
  p_sender_phone TEXT,
  p_sender_name TEXT,
  p_amount NUMERIC,
  p_receiver_wallet TEXT DEFAULT NULL,
  p_transaction_time TIMESTAMPTZ DEFAULT NULL,
  p_device_id TEXT DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  req RECORD;
  new_balance NUMERIC;
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

  UPDATE wallets
    SET balance = balance + req.amount, updated_at = now()
  WHERE user_id = req.customer_id
  RETURNING balance INTO new_balance;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Wallet not found for user %', req.customer_id;
  END IF;

  UPDATE wallet_topup_requests SET
    status = 'approved', scan_status = 'approved',
    processed_at = now(), confirmed_at = now(),
    matched_automatically = TRUE,
    transaction_id = p_transaction_id,
    sender_name = COALESCE(p_sender_name, sender_name),
    assigned_device_id = COALESCE(p_device_id, assigned_device_id),
    failure_reason = NULL
  WHERE id = p_order_id;

  INSERT INTO wallet_transactions(customer_id, type, amount, balance_after, reason, reference)
  VALUES (req.customer_id, 'credit', req.amount, new_balance,
    'Vodafone Cash auto-confirmation', p_transaction_id);

  INSERT INTO notifications(user_id, type, title, body)
  VALUES (req.customer_id, 'wallet_topup', 'تم شحن رصيدك تلقائياً',
    'تمت إضافة ' || req.amount || ' Credit. رصيدك الآن: ' || new_balance || ' Credit.');

  IF p_device_id IS NOT NULL THEN
    UPDATE sms_device_status SET last_order_processed_at = now(), updated_at = now()
    WHERE device_id = p_device_id;
  END IF;

  RETURN jsonb_build_object(
    'ok', true, 'confirmed', true,
    'order_id', p_order_id, 'new_balance', new_balance,
    'transaction_id', p_transaction_id
  );
END;
$$;

-- 7. Updated complete_device_task using atomic confirm
CREATE OR REPLACE FUNCTION complete_device_task(
  p_task_id uuid,
  p_status TEXT,
  p_result_data JSONB DEFAULT NULL,
  p_failure_reason TEXT DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  task RECORD;
  txn_id TEXT;
  confirm_result JSONB;
  new_scan_status TEXT;
BEGIN
  SELECT * INTO task FROM pending_tasks WHERE id = p_task_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'task_not_found');
  END IF;

  IF task.task_status = 'completed' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true, 'task_status', 'completed');
  END IF;

  txn_id := p_result_data->>'transaction_id';

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
      (p_result_data->>'amount')::NUMERIC, txn_id,
      p_result_data->>'sms_body', (confirm_result->>'ok')::BOOLEAN)
    ON CONFLICT (transaction_id) DO NOTHING;

    UPDATE sms_device_status SET last_sms_at = now(), updated_at = now()
    WHERE device_id = task.device_id;

    RETURN confirm_result;
  ELSE
    new_scan_status := CASE
      WHEN p_status = 'not_found' THEN 'not_found'
      WHEN p_failure_reason ILIKE '%amount%' OR p_failure_reason ILIKE '%مبلغ%' THEN 'amount_mismatch'
      ELSE 'manual_review'
    END;

    UPDATE pending_tasks SET
      task_status    = 'completed',
      result_status  = 'not_found',
      failure_reason = p_failure_reason,
      completed_at   = now(), updated_at = now()
    WHERE id = p_task_id;

    UPDATE wallet_topup_requests SET
      status = 'pending', scan_status = new_scan_status,
      failure_reason = COALESCE(p_failure_reason, 'لم يتم العثور على رسالة مطابقة')
    WHERE id = task.request_id AND status NOT IN ('approved', 'rejected');

    RETURN jsonb_build_object('ok', true, 'auto_approved', false,
      'reason', COALESCE(p_failure_reason, 'not_found'));
  END IF;
END;
$$;

-- 8. get_device_pending_tasks richer response
CREATE OR REPLACE FUNCTION get_device_pending_tasks(p_device_id TEXT)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE result jsonb;
BEGIN
  SELECT jsonb_agg(
    jsonb_build_object(
      'task_id', pt.id,
      'request_id', pt.request_id,
      'amount_requested', pt.amount_requested,
      'sender_phone_requested', pt.sender_phone_requested,
      'sender_name_requested', pt.sender_name_requested,
      'fingerprint_amount', pt.fingerprint_amount,
      'credits_amount', pt.credits_amount,
      'created_at', pt.created_at,
      'retry_count', pt.retry_count
    )
  ) INTO result
  FROM pending_tasks pt
  WHERE pt.device_id = p_device_id
    AND pt.task_status IN ('pending', 'assigned')
  ORDER BY pt.created_at ASC;
  RETURN COALESCE(result, '[]'::jsonb);
END;
$$;

-- 9. Realtime for device status (idempotent)
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE sms_device_status;
  EXCEPTION WHEN others THEN NULL;
  END;
END $$;
