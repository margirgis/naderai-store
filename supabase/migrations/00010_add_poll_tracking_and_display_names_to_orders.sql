-- Add poll tracking to orders
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS poll_count integer NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_polled_at timestamptz,
  ADD COLUMN IF NOT EXISTS provider_request_id text;

-- Ensure offer_link, two_fa_link, activation_data, idempotency_key, safe_error_code, safe_error_message, webhook_received_at exist
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS offer_link text,
  ADD COLUMN IF NOT EXISTS two_fa_link text,
  ADD COLUMN IF NOT EXISTS activation_data jsonb,
  ADD COLUMN IF NOT EXISTS idempotency_key text,
  ADD COLUMN IF NOT EXISTS safe_error_code text,
  ADD COLUMN IF NOT EXISTS safe_error_message text,
  ADD COLUMN IF NOT EXISTS webhook_received_at timestamptz;

-- display_name_ar/en on provider_services
ALTER TABLE provider_services
  ADD COLUMN IF NOT EXISTS display_name_ar text,
  ADD COLUMN IF NOT EXISTS display_name_en text;

-- Set display names for extract_18m
UPDATE provider_services
SET
  display_name_ar = 'جيميناي برو 18 شهر',
  display_name_en = 'Gemini AI Pro — 18 Months',
  store_enabled = true,
  customer_price = 1.5
WHERE provider_code = 'extract_18m';

-- Notifications must have order_id column
ALTER TABLE notifications
  ADD COLUMN IF NOT EXISTS order_id uuid REFERENCES orders(id) ON DELETE SET NULL;

-- Index for fast dedup check
CREATE INDEX IF NOT EXISTS notifs_user_order_type ON notifications(user_id, order_id, type);
