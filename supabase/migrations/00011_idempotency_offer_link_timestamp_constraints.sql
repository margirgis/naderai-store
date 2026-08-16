-- 1. Idempotency unique constraint on orders
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS idempotency_key text,
  ADD COLUMN IF NOT EXISTS offer_link_created_at timestamptz;

-- Unique per user+service: prevent duplicate active orders for same service
-- (Only enforce on non-terminal statuses via partial index)
CREATE UNIQUE INDEX IF NOT EXISTS orders_idempotency_key_unique
  ON orders (customer_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

-- 2. offer_link_created_at: server timestamp when offer_link first appeared
-- Already added above, ensure index for quick lookup
CREATE INDEX IF NOT EXISTS orders_offer_link_not_null
  ON orders (id) WHERE offer_link IS NOT NULL;

-- 3. RLS: ensure customers can only read/insert their own orders
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "customers_select_own_orders" ON orders;
CREATE POLICY "customers_select_own_orders" ON orders
  FOR SELECT USING (
    auth.uid() = customer_id
    OR EXISTS (
      SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'
    )
  );

DROP POLICY IF EXISTS "customers_insert_own_orders" ON orders;
CREATE POLICY "customers_insert_own_orders" ON orders
  FOR INSERT WITH CHECK (auth.uid() = customer_id);

-- service_role bypasses RLS (Edge Functions use service_role key)

-- 4. Notifications RLS
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_own_notifications" ON notifications;
CREATE POLICY "users_select_own_notifications" ON notifications
  FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "users_update_own_notifications" ON notifications;
CREATE POLICY "users_update_own_notifications" ON notifications
  FOR UPDATE USING (auth.uid() = user_id);
