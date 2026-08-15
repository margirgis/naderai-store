-- 1. Add balance fields to provider_config
ALTER TABLE provider_config
  ADD COLUMN IF NOT EXISTS balance_credit numeric,
  ADD COLUMN IF NOT EXISTS balance_currency text,
  ADD COLUMN IF NOT EXISTS balance_synced_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_error_code text,
  ADD COLUMN IF NOT EXISTS last_response_time_ms integer;

-- 2. Ensure service_role bypass for provider_services (service_role bypasses RLS by default in Postgres)
-- The RLS policies with service_role key should already bypass — add explicit policy just in case
DROP POLICY IF EXISTS "service_role_full_access_services" ON provider_services;
CREATE POLICY "service_role_full_access_services" ON provider_services
  FOR ALL USING (true) WITH CHECK (true);
-- Note: service_role always bypasses RLS but this ensures no conflict

-- 3. Enable Realtime on key tables
ALTER PUBLICATION supabase_realtime ADD TABLE provider_services;
ALTER PUBLICATION supabase_realtime ADD TABLE provider_config;
ALTER PUBLICATION supabase_realtime ADD TABLE orders;