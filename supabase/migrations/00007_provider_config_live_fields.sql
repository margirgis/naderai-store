-- Add live-specific tracking columns to provider_config
ALTER TABLE provider_config
  ADD COLUMN IF NOT EXISTS services_count integer,
  ADD COLUMN IF NOT EXISTS services_available integer,
  ADD COLUMN IF NOT EXISTS services_maintenance integer,
  ADD COLUMN IF NOT EXISTS orders_total integer,
  ADD COLUMN IF NOT EXISTS orders_active integer,
  ADD COLUMN IF NOT EXISTS stats_synced_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_request_id text,
  ADD COLUMN IF NOT EXISTS last_error_message text;

-- Update environment field to 'live'
UPDATE provider_config SET environment = 'live'
WHERE environment = 'sandbox' OR environment IS NULL;