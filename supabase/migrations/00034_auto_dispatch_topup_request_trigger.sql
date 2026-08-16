CREATE OR REPLACE FUNCTION public.auto_dispatch_topup_request()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_device_id TEXT;
  v_result JSONB;
  v_online_devices INTEGER;
BEGIN
  -- Only act on fresh pending requests
  IF NEW.status IS DISTINCT FROM 'pending' THEN
    RETURN NEW;
  END IF;

  -- Find the most recently active online device (heartbeat within last 90 seconds)
  SELECT device_id INTO v_device_id
  FROM sms_device_status
  WHERE last_heartbeat_at >= (now() - INTERVAL '90 seconds')
    AND is_active = true
  ORDER BY last_heartbeat_at DESC
  LIMIT 1;

  -- If a device is online, assign the request immediately
  IF v_device_id IS NOT NULL THEN
    v_result := assign_task_to_device(NEW.id, v_device_id);
    IF (v_result->>'ok')::boolean THEN
      -- Create admin notification about immediate dispatch
      PERFORM create_admin_notification(
        'طلب شحن أُرسل للجهاز تلقائياً 📱',
        'الطلب #' || COALESCE(NEW.order_number::text, NEW.id::text) || ' أُرسل للجهاز ' || substring(v_device_id from 1 for 12) || ' فور الإنشاء',
        'auto_dispatch',
        NEW.id::text,
        v_device_id
      );
    END IF;
  ELSE
    -- No online device; leave pending and notify admin so they know it is waiting
    PERFORM create_admin_notification(
      'طلب شحن جديد في انتظار جهاز متصل ⏳',
      'الطلب #' || COALESCE(NEW.order_number::text, NEW.id::text) || ' بانتظار توصيله لأقرب جهاز متصل',
      'pending_no_device',
      NEW.id::text,
      NULL
    );
  END IF;

  RETURN NEW;
END;
$$;

-- Drop the trigger if it already exists
DROP TRIGGER IF EXISTS trg_auto_dispatch_topup_request ON public.wallet_topup_requests;

-- Create the AFTER INSERT trigger
CREATE TRIGGER trg_auto_dispatch_topup_request
AFTER INSERT ON public.wallet_topup_requests
FOR EACH ROW
EXECUTE FUNCTION public.auto_dispatch_topup_request();