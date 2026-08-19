
-- Migration 00062: Add sms_body, sender_name, sender_phone to wallet_topup_requests
-- so the app can display SMS details in the order detail view
-- Join via sms_logs_devices.request_id = wallet_topup_requests.id

-- Add columns to store SMS data directly on the topup request (denormalized for fast RLS-safe reads)
ALTER TABLE public.wallet_topup_requests
  ADD COLUMN IF NOT EXISTS sms_body        TEXT    DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS sender_name     TEXT    DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS sender_phone_confirmed TEXT DEFAULT NULL;

-- Backfill existing confirmed rows from sms_logs_devices
UPDATE public.wallet_topup_requests wtr
SET
  sms_body               = sld.sms_body,
  sender_name            = sld.sender_name,
  sender_phone_confirmed = sld.sender_phone
FROM public.sms_logs_devices sld
WHERE sld.request_id = wtr.id
  AND sld.matched = true
  AND wtr.scan_status IN ('approved','confirmed')
  AND wtr.sms_body IS NULL;

-- Create trigger to auto-populate sms_body on sms_logs_devices INSERT
CREATE OR REPLACE FUNCTION sync_sms_body_to_topup()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $$
BEGIN
  IF NEW.matched = true AND NEW.request_id IS NOT NULL THEN
    UPDATE wallet_topup_requests
    SET
      sms_body               = COALESCE(wallet_topup_requests.sms_body, NEW.sms_body),
      sender_name            = COALESCE(wallet_topup_requests.sender_name, NEW.sender_name),
      sender_phone_confirmed = COALESCE(wallet_topup_requests.sender_phone_confirmed, NEW.sender_phone)
    WHERE id = NEW.request_id;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_sms_body ON public.sms_logs_devices;
CREATE TRIGGER trg_sync_sms_body
  AFTER INSERT ON public.sms_logs_devices
  FOR EACH ROW EXECUTE FUNCTION sync_sms_body_to_topup();

-- Verify backfill
SELECT id, order_number, sms_body, sender_name FROM wallet_topup_requests
WHERE sms_body IS NOT NULL;
