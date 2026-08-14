
-- Phase 2: Extend profiles table with wallet_balance, status
ALTER TABLE profiles
  ADD COLUMN IF NOT EXISTS wallet_balance numeric(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','banned'));

-- Extend provider_services with store-layer fields
ALTER TABLE provider_services
  ADD COLUMN IF NOT EXISTS customer_price numeric(12,4),
  ADD COLUMN IF NOT EXISTS store_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS max_items_per_request integer NOT NULL DEFAULT 100,
  ADD COLUMN IF NOT EXISTS description_ar text;

-- Set customer_price = provider_credit_price where null (temporary sandbox parity)
UPDATE provider_services
  SET customer_price = provider_credit_price
  WHERE customer_price IS NULL;
