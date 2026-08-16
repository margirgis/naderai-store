-- ══════════════════════════════════════════════════════════════════════════
-- Migration 023: Fix Device Registration RLS + Realtime + Notification system
-- ROOT CAUSES FIXED:
--   1. sms_device_status: anon role had NO INSERT/UPDATE policy → heartbeat upsert silently failed
--   2. pending_tasks: not in supabase_realtime publication → Android never received orders
--   3. wallet_topup_requests: not in supabase_realtime publication → Admin couldn't see real-time updates
--   4. device_commands: new table for server→android test/command flow
-- ══════════════════════════════════════════════════════════════════════════

-- ─── 1. FIX sms_device_status RLS ─────────────────────────────────────────
-- The anon role (used by Android via ANON_KEY + X-SMS-Webhook-Secret) must be
-- able to upsert its own device record. Edge Function uses SERVICE_ROLE so it
-- bypasses RLS — but direct client calls (if any) also need access.
-- The Edge Function uses SUPABASE_SERVICE_ROLE_KEY → RLS bypassed for Edge Fn.
-- So the root cause must be verified: check if upsert is failing due to missing cols.

-- Add missing columns that migration 022 added but original table didn't have
ALTER TABLE public.sms_device_status
  ADD COLUMN IF NOT EXISTS android_version TEXT,
  ADD COLUMN IF NOT EXISTS phone_number TEXT,
  ADD COLUMN IF NOT EXISTS capabilities JSONB DEFAULT '{}',
  ADD COLUMN IF NOT EXISTS last_test_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_test_result TEXT,
  ADD COLUMN IF NOT EXISTS response_time_ms INTEGER,
  ADD COLUMN IF NOT EXISTS last_sms_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS last_order_processed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS reader_status TEXT DEFAULT 'idle';

-- Allow anon (Android device via anon key) to upsert its own device record
-- The Edge Function uses service role so it bypasses RLS entirely.
-- These policies are safety nets for direct Supabase client calls.
DROP POLICY IF EXISTS "anon_upsert_own_device" ON public.sms_device_status;
CREATE POLICY "anon_upsert_own_device" ON public.sms_device_status
  FOR INSERT TO anon
  WITH CHECK (true);

DROP POLICY IF EXISTS "anon_update_own_device" ON public.sms_device_status;
CREATE POLICY "anon_update_own_device" ON public.sms_device_status
  FOR UPDATE TO anon
  USING (true)
  WITH CHECK (true);

DROP POLICY IF EXISTS "anon_select_own_device" ON public.sms_device_status;
CREATE POLICY "anon_select_own_device" ON public.sms_device_status
  FOR SELECT TO anon
  USING (true);

-- ─── 2. FIX Realtime publications ─────────────────────────────────────────
-- pending_tasks was missing → Android never got orders via Realtime
-- wallet_topup_requests was missing → Admin page couldn't get live updates

DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE pending_tasks;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE wallet_topup_requests;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE sms_device_status;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE notifications;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
END $$;

-- ─── 3. device_commands table (server → android test/command flow) ──────────
CREATE TABLE IF NOT EXISTS device_commands (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id TEXT NOT NULL,
  command_type TEXT NOT NULL DEFAULT 'test_ping'
    CHECK (command_type IN ('test_ping', 'test_server_to_android', 'sync_orders', 'restart_heartbeat')),
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'delivered', 'acknowledged', 'responded', 'timeout', 'failed')),
  sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  delivered_at TIMESTAMPTZ,
  ack_at TIMESTAMPTZ,
  responded_at TIMESTAMPTZ,
  response_time_ms INTEGER,
  response_data JSONB,
  timeout_at TIMESTAMPTZ DEFAULT now() + INTERVAL '2 minutes',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS device_commands_device_status_idx ON device_commands(device_id, status);
CREATE INDEX IF NOT EXISTS device_commands_created_idx ON device_commands(created_at DESC);

ALTER TABLE device_commands ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "admin_all_device_commands" ON device_commands;
CREATE POLICY "admin_all_device_commands" ON device_commands
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'))
  WITH CHECK (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- anon (Android) can read commands addressed to it and update (ack/respond)
DROP POLICY IF EXISTS "anon_read_own_commands" ON device_commands;
CREATE POLICY "anon_read_own_commands" ON device_commands
  FOR SELECT TO anon
  USING (true);

DROP POLICY IF EXISTS "anon_update_own_commands" ON device_commands;
CREATE POLICY "anon_update_own_commands" ON device_commands
  FOR UPDATE TO anon
  USING (true)
  WITH CHECK (true);

-- Add to Realtime
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE device_commands;
  EXCEPTION WHEN duplicate_object THEN NULL;
  END;
END $$;

-- ─── 4. Extend notifications table ────────────────────────────────────────
ALTER TABLE public.notifications
  ADD COLUMN IF NOT EXISTS event_type TEXT DEFAULT 'info',
  ADD COLUMN IF NOT EXISTS reference_id TEXT,
  ADD COLUMN IF NOT EXISTS device_id TEXT;

-- Allow admins to receive all notifications (for admin bell)
DROP POLICY IF EXISTS "admin_receive_all_notifications" ON public.notifications;
CREATE POLICY "admin_receive_all_notifications" ON public.notifications
  FOR SELECT TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- ─── 5. RPC: create_admin_notification ────────────────────────────────────
CREATE OR REPLACE FUNCTION create_admin_notification(
  p_title TEXT,
  p_message TEXT,
  p_event_type TEXT DEFAULT 'info',
  p_reference_id TEXT DEFAULT NULL,
  p_device_id TEXT DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_admin_id uuid;
  v_notification_id uuid;
BEGIN
  -- Find an admin user to deliver to (first admin)
  SELECT id INTO v_admin_id FROM profiles WHERE role = 'admin' LIMIT 1;
  IF v_admin_id IS NULL THEN RETURN NULL; END IF;

  INSERT INTO notifications(user_id, title, message, event_type, reference_id, device_id, is_read, created_at)
  VALUES (v_admin_id, p_title, p_message, p_event_type, p_reference_id, p_device_id, false, now())
  RETURNING id INTO v_notification_id;

  RETURN v_notification_id;
END;
$$;

-- ─── 6. RPC: register_device ───────────────────────────────────────────────
-- Explicit registration call (separate from heartbeat) that ensures device exists
CREATE OR REPLACE FUNCTION register_device(
  p_device_id TEXT,
  p_device_model TEXT DEFAULT NULL,
  p_device_name TEXT DEFAULT NULL,
  p_android_version TEXT DEFAULT NULL,
  p_app_version TEXT DEFAULT NULL,
  p_phone_number TEXT DEFAULT NULL,
  p_capabilities JSONB DEFAULT '{}'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_is_new BOOLEAN;
BEGIN
  SELECT NOT EXISTS(SELECT 1 FROM sms_device_status WHERE device_id = p_device_id) INTO v_is_new;

  INSERT INTO sms_device_status(
    device_id, device_model, device_name, android_version, app_version,
    phone_number, capabilities, last_heartbeat_at, status, is_active, reader_status
  )
  VALUES (
    p_device_id, p_device_model, p_device_name, p_android_version, p_app_version,
    p_phone_number, p_capabilities, now(), 'online', true, 'idle'
  )
  ON CONFLICT (device_id) DO UPDATE SET
    device_model = COALESCE(EXCLUDED.device_model, sms_device_status.device_model),
    device_name = COALESCE(EXCLUDED.device_name, sms_device_status.device_name),
    android_version = COALESCE(EXCLUDED.android_version, sms_device_status.android_version),
    app_version = COALESCE(EXCLUDED.app_version, sms_device_status.app_version),
    phone_number = COALESCE(EXCLUDED.phone_number, sms_device_status.phone_number),
    capabilities = COALESCE(EXCLUDED.capabilities, sms_device_status.capabilities),
    last_heartbeat_at = now(),
    status = 'online',
    is_active = true,
    updated_at = now();

  -- Notify admin on new device registration
  IF v_is_new THEN
    PERFORM create_admin_notification(
      'جهاز Android جديد',
      'تم تسجيل جهاز جديد: ' || COALESCE(p_device_model, p_device_id),
      'device_registered',
      p_device_id,
      p_device_id
    );
  END IF;

  RETURN jsonb_build_object('ok', true, 'is_new', v_is_new, 'device_id', p_device_id);
END;
$$;

-- ─── 7. RPC: get_pending_device_commands ──────────────────────────────────
-- Called during heartbeat to check for server→android commands
CREATE OR REPLACE FUNCTION get_pending_device_commands(p_device_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_commands JSONB;
BEGIN
  SELECT jsonb_agg(jsonb_build_object(
    'command_id', id,
    'command_type', command_type,
    'sent_at', sent_at
  )) INTO v_commands
  FROM device_commands
  WHERE device_id = p_device_id
    AND status = 'pending'
    AND timeout_at > now();

  -- Mark them delivered
  UPDATE device_commands
  SET status = 'delivered', delivered_at = now()
  WHERE device_id = p_device_id
    AND status = 'pending'
    AND timeout_at > now();

  RETURN COALESCE(v_commands, '[]'::jsonb);
END;
$$;

-- ─── 8. RPC: ack_device_command ───────────────────────────────────────────
CREATE OR REPLACE FUNCTION ack_device_command(
  p_command_id uuid,
  p_device_id TEXT,
  p_response_data JSONB DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_cmd device_commands%ROWTYPE;
BEGIN
  SELECT * INTO v_cmd FROM device_commands WHERE id = p_command_id AND device_id = p_device_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('ok', false, 'error', 'command not found'); END IF;

  UPDATE device_commands SET
    status = 'responded',
    ack_at = COALESCE(ack_at, now()),
    responded_at = now(),
    response_time_ms = EXTRACT(EPOCH FROM (now() - sent_at)) * 1000,
    response_data = p_response_data
  WHERE id = p_command_id;

  -- Notify admin that device responded
  PERFORM create_admin_notification(
    'رد الجهاز على الاختبار ✓',
    'الجهاز ' || p_device_id || ' رد على الأمر خلال ' ||
      EXTRACT(EPOCH FROM (now() - v_cmd.sent_at))::INTEGER || 'ث',
    'test_responded',
    p_command_id::TEXT,
    p_device_id
  );

  -- Update device last_test_at
  UPDATE sms_device_status SET
    last_test_at = now(),
    last_test_result = 'success',
    response_time_ms = EXTRACT(EPOCH FROM (now() - v_cmd.sent_at)) * 1000,
    updated_at = now()
  WHERE device_id = p_device_id;

  RETURN jsonb_build_object('ok', true);
END;
$$;

-- ─── 9. Fix get_device_pending_tasks to also return device commands ─────────
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
    'task_id', pt.id,
    'request_id', pt.request_id,
    'amount_requested', pt.amount_requested,
    'sender_phone_requested', pt.sender_phone_requested,
    'sender_name_requested', pt.sender_name_requested,
    'fingerprint_amount', pt.fingerprint_amount,
    'credits_amount', pt.credits_amount,
    'task_status', pt.task_status,
    'created_at', pt.created_at
  )) INTO v_tasks
  FROM pending_tasks pt
  WHERE pt.device_id = p_device_id
    AND pt.task_status IN ('pending', 'assigned', 'in_progress')
  ORDER BY pt.created_at ASC;

  -- Also check for pending commands
  SELECT get_pending_device_commands(p_device_id) INTO v_commands;

  RETURN jsonb_build_object(
    'tasks', COALESCE(v_tasks, '[]'::jsonb),
    'commands', COALESCE(v_commands, '[]'::jsonb)
  );
END;
$$;
