
-- Migration 00059: Fix sms_logs_devices — add UNIQUE on transaction_id
-- complete_device_task uses ON CONFLICT(transaction_id) but constraint was missing

-- Add unique constraint if not exists
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'sms_logs_devices'::regclass
      AND contype IN ('u','p')
      AND conname LIKE '%transaction_id%'
  ) THEN
    ALTER TABLE sms_logs_devices
      ADD CONSTRAINT sms_logs_devices_transaction_id_unique UNIQUE (transaction_id);
    RAISE NOTICE '[MIGRATION 059] Added UNIQUE(transaction_id) to sms_logs_devices';
  ELSE
    RAISE NOTICE '[MIGRATION 059] UNIQUE(transaction_id) already exists on sms_logs_devices';
  END IF;
END $$;

-- Verify
SELECT conname, contype, pg_get_constraintdef(oid) AS def
FROM pg_constraint
WHERE conrelid = 'sms_logs_devices'::regclass
ORDER BY contype;
