
-- =============================================
-- Phase 1: Provider Integration Tables
-- =============================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- User role enum
CREATE TYPE public.user_role AS ENUM ('user', 'admin');

-- Profiles table (for auth)
CREATE TABLE public.profiles (
  id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  email text,
  phone text,
  role user_role NOT NULL DEFAULT 'user',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Auto-sync profiles on new user
CREATE OR REPLACE FUNCTION handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  INSERT INTO public.profiles (id, email, phone, role)
  VALUES (NEW.id, NEW.email, NEW.phone, 'user'::public.user_role);
  RETURN NEW;
END;
$$;

CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION handle_new_user();

-- Helper: get user role (avoids RLS recursion)
CREATE OR REPLACE FUNCTION get_user_role(uid uuid)
RETURNS user_role
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT role FROM profiles WHERE id = uid;
$$;

-- RLS on profiles
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Admins have full access to profiles" ON profiles
  FOR ALL TO authenticated USING (get_user_role(auth.uid()) = 'admin'::user_role);

CREATE POLICY "Users can view their own profile" ON profiles
  FOR SELECT TO authenticated USING (auth.uid() = id);

CREATE POLICY "Users can update their own profile" ON profiles
  FOR UPDATE TO authenticated USING (auth.uid() = id)
  WITH CHECK (role IS NOT DISTINCT FROM get_user_role(auth.uid()));

-- Public view
CREATE VIEW public_profiles AS
  SELECT id, role FROM profiles;

-- =============================================
-- Provider Configuration Table
-- =============================================
CREATE TABLE public.provider_config (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  environment text NOT NULL DEFAULT 'sandbox',
  base_url text NOT NULL DEFAULT 'https://api.geminioffer.web.id/api/v1',
  key_status text NOT NULL DEFAULT 'unknown', -- 'valid', 'invalid', 'unknown'
  last_health_check_at timestamptz,
  last_health_check_success boolean,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.provider_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Admins have full access to provider_config" ON provider_config
  FOR ALL TO authenticated USING (get_user_role(auth.uid()) = 'admin'::user_role);

-- Insert default config row
INSERT INTO public.provider_config (environment, base_url, key_status)
VALUES ('sandbox', 'https://api.geminioffer.web.id/api/v1', 'unknown');

-- =============================================
-- Provider Services Table
-- =============================================
CREATE TABLE public.provider_services (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_code text UNIQUE NOT NULL,
  name text NOT NULL,
  status text NOT NULL DEFAULT 'active', -- 'active', 'maintenance', 'inactive'
  input_type text, -- 'accounts', 'quantity', etc.
  provider_credit_price numeric(18,4),
  provider_idr_price numeric(18,2),
  provider_usd_price numeric(18,6),
  discount_percent numeric(5,2),
  final_credit_price numeric(18,4),
  max_items_per_request integer,
  returns boolean DEFAULT false,
  is_enabled boolean NOT NULL DEFAULT true,
  last_synced_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.provider_services ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Admins have full access to provider_services" ON provider_services
  FOR ALL TO authenticated USING (get_user_role(auth.uid()) = 'admin'::user_role);

-- =============================================
-- Provider Logs Table
-- =============================================
CREATE TABLE public.provider_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  operation text NOT NULL, -- 'health_check', 'sync_services', 'refresh_balance', 'test_connection'
  success boolean NOT NULL,
  http_status integer,
  provider_request_id text,
  response_time_ms integer,
  error_code text,
  error_message text, -- sanitized, no secrets
  created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.provider_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Admins have full access to provider_logs" ON provider_logs
  FOR ALL TO authenticated USING (get_user_role(auth.uid()) = 'admin'::user_role);

-- =============================================
-- Updated_at triggers
-- =============================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER update_profiles_updated_at
  BEFORE UPDATE ON profiles
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_provider_config_updated_at
  BEFORE UPDATE ON provider_config
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_provider_services_updated_at
  BEFORE UPDATE ON provider_services
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();
