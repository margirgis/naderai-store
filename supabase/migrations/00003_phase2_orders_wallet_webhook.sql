
-- ============================================================
-- ORDERS
-- ============================================================
CREATE TABLE orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
  service_id uuid NOT NULL REFERENCES provider_services(id) ON DELETE RESTRICT,
  provider_service_code text NOT NULL,
  provider_task_id text,
  reference text UNIQUE NOT NULL,
  quantity integer,
  customer_total numeric(12,4) NOT NULL,
  provider_cost numeric(12,4) NOT NULL,
  status text NOT NULL DEFAULT 'creating'
    CHECK (status IN ('creating','queued','processing','success','partial','failed','cancelled','rejected')),
  result_data jsonb,
  result_available boolean NOT NULL DEFAULT false,
  provider_raw_response jsonb,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX orders_customer_id_idx ON orders(customer_id);
CREATE INDEX orders_provider_task_id_idx ON orders(provider_task_id);
CREATE INDEX orders_status_idx ON orders(status);

-- ============================================================
-- WALLET TRANSACTIONS
-- ============================================================
CREATE TABLE wallet_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
  type text NOT NULL CHECK (type IN ('credit','debit','hold','release')),
  amount numeric(12,4) NOT NULL,
  balance_after numeric(12,4) NOT NULL,
  reason text NOT NULL,
  order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
  reference text,
  created_by uuid REFERENCES profiles(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX wallet_transactions_customer_id_idx ON wallet_transactions(customer_id);
CREATE INDEX wallet_transactions_order_id_idx ON wallet_transactions(order_id);

-- ============================================================
-- WEBHOOK EVENTS (idempotency + audit)
-- ============================================================
CREATE TABLE webhook_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id text UNIQUE NOT NULL,
  event_type text NOT NULL,
  provider_task_id text,
  payload jsonb NOT NULL,
  processed boolean NOT NULL DEFAULT false,
  processed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX webhook_events_event_id_idx ON webhook_events(event_id);
CREATE INDEX webhook_events_provider_task_id_idx ON webhook_events(provider_task_id);

-- ============================================================
-- updated_at trigger for orders
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$;

CREATE TRIGGER orders_updated_at
  BEFORE UPDATE ON orders
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- RLS
-- ============================================================
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallet_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_events ENABLE ROW LEVEL SECURITY;

-- Helper: is_admin (SECURITY DEFINER avoids RLS self-loop)
CREATE OR REPLACE FUNCTION is_admin()
RETURNS boolean LANGUAGE sql SECURITY DEFINER STABLE AS $$
  SELECT EXISTS (
    SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'
  );
$$;

-- ---------- orders ----------
-- Customer: own orders only
CREATE POLICY "customer_select_own_orders" ON orders
  FOR SELECT TO authenticated
  USING (customer_id = auth.uid() OR is_admin());

CREATE POLICY "admin_all_orders" ON orders
  FOR ALL TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- ---------- wallet_transactions ----------
CREATE POLICY "customer_select_own_wallet" ON wallet_transactions
  FOR SELECT TO authenticated
  USING (customer_id = auth.uid() OR is_admin());

CREATE POLICY "admin_all_wallet" ON wallet_transactions
  FOR ALL TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- ---------- webhook_events ----------
CREATE POLICY "admin_only_webhooks" ON webhook_events
  FOR ALL TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- ---------- profiles: customers can read/update own ----------
DROP POLICY IF EXISTS "profiles_select_own" ON profiles;
DROP POLICY IF EXISTS "profiles_update_own" ON profiles;

CREATE POLICY "profiles_select_own_or_admin" ON profiles
  FOR SELECT TO authenticated
  USING (id = auth.uid() OR is_admin());

CREATE POLICY "profiles_update_own" ON profiles
  FOR UPDATE TO authenticated
  USING (id = auth.uid())
  WITH CHECK (id = auth.uid());

CREATE POLICY "admin_update_profiles" ON profiles
  FOR UPDATE TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- ---------- provider_services: customers can SELECT active store services ----------
DROP POLICY IF EXISTS "services_admin_all" ON provider_services;

CREATE POLICY "customer_select_store_services" ON provider_services
  FOR SELECT TO authenticated
  USING (
    (store_enabled = true AND status = 'active') OR is_admin()
  );

CREATE POLICY "anon_no_services" ON provider_services
  FOR SELECT TO anon
  USING (false);

CREATE POLICY "admin_manage_services" ON provider_services
  FOR ALL TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- ---------- profiles INSERT for new sign-ups ----------
CREATE POLICY "profiles_insert_own" ON profiles
  FOR INSERT TO authenticated
  WITH CHECK (id = auth.uid());
