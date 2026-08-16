DROP FUNCTION IF EXISTS create_admin_notification(text,text,text,text,text);

CREATE OR REPLACE FUNCTION create_admin_notification(
  p_title TEXT,
  p_message TEXT,
  p_event_type TEXT,
  p_reference_id TEXT,
  p_device_id TEXT
)
RETURNS uuid AS $$
DECLARE
  v_admin_id uuid;
  v_notification_id uuid;
BEGIN
  SELECT id INTO v_admin_id FROM profiles WHERE role = 'admin' LIMIT 1;
  IF v_admin_id IS NULL THEN RETURN NULL; END IF;

  INSERT INTO notifications(user_id, type, title, body, event_type, reference_id, device_id, is_read, created_at)
  VALUES (
    v_admin_id,
    p_event_type,
    p_title,
    p_message,
    p_event_type,
    p_reference_id,
    p_device_id,
    false,
    now()
  )
  RETURNING id INTO v_notification_id;

  RETURN v_notification_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;