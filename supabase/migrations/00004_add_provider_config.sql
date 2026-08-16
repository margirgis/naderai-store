
-- provider_config: stores current connection state (no API key stored here)
CREATE TABLE IF NOT EXISTS provider_config (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  environment     text NOT NULL DEFAULT 'sandbox',
  base_url        text NOT NULL DEFAULT 'https://api.geminioffer.web.id/api/v1',
  key_status      text NOT NULL DEFAULT 'unknown'
    CHECK (key_status IN ('valid', 'invalid', 'unknown')),
  last_health_check_at      timestamptz,
  last_health_check_success boolean,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Only admins can read/write this table (no customer access)
ALTER TABLE provider_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "admin_read_provider_config"
  ON provider_config FOR SELECT
  USING (
    EXISTS (
      SELECT 1 FROM profiles p
      WHERE p.id = auth.uid() AND p.role = 'admin'
    )
  );

CREATE POLICY "admin_write_provider_config"
  ON provider_config FOR ALL
  USING (
    EXISTS (
      SELECT 1 FROM profiles p
      WHERE p.id = auth.uid() AND p.role = 'admin'
    )
  );

-- Seed one row if empty
INSERT INTO provider_config (environment, base_url, key_status)
SELECT 'sandbox', 'https://api.geminioffer.web.id/api/v1', 'unknown'
WHERE NOT EXISTS (SELECT 1 FROM provider_config);

-- Auto-updated_at trigger
CREATE OR REPLACE FUNCTION update_provider_config_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$;

CREATE TRIGGER trg_provider_config_updated_at
  BEFORE UPDATE ON provider_config
  FOR EACH ROW EXECUTE FUNCTION update_provider_config_updated_at();
