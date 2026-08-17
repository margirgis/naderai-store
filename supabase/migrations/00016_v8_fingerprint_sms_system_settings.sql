-- Extend wallet_topup_requests
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS fingerprint_amount NUMERIC(10,2),
  ADD COLUMN IF NOT EXISTS sender_name TEXT,
  ADD COLUMN IF NOT EXISTS transaction_id TEXT,
  ADD COLUMN IF NOT EXISTS matched_automatically BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS failure_reason TEXT,
  ADD COLUMN IF NOT EXISTS credits_requested INTEGER,
  ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ;

-- sms_logs table
CREATE TABLE IF NOT EXISTS sms_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_phone TEXT,
  sender_name TEXT,
  amount NUMERIC(10,2),
  transaction_id TEXT,
  sms_body TEXT,
  matched_request_id UUID REFERENCES wallet_topup_requests(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE sms_logs ENABLE ROW LEVEL SECURITY;
CREATE POLICY "admin_all_sms_logs" ON sms_logs
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- system_settings table
CREATE TABLE IF NOT EXISTS system_settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE system_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY "admin_all_settings" ON system_settings
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));
CREATE POLICY "customer_read_settings" ON system_settings
  FOR SELECT USING (true);

-- Seed default vodafone cash number
INSERT INTO system_settings (key, value)
VALUES ('vodafone_cash_number', '01097273680')
ON CONFLICT (key) DO NOTHING;
