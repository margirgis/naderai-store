-- Notifications table
CREATE TABLE IF NOT EXISTS notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  type TEXT NOT NULL, -- order_created | order_updated | order_success | order_failed | offer_link_ready
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  order_id UUID REFERENCES orders(id) ON DELETE SET NULL,
  is_read BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast unread lookup
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications(user_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_order ON notifications(order_id);

-- RLS
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

-- Users see only their own
CREATE POLICY "user_read_own_notifications" ON notifications
  FOR SELECT USING (auth.uid() = user_id);

-- Only backend (service role) inserts
CREATE POLICY "service_insert_notifications" ON notifications
  FOR INSERT WITH CHECK (true);

-- User marks read
CREATE POLICY "user_update_own_notifications" ON notifications
  FOR UPDATE USING (auth.uid() = user_id)
  WITH CHECK (auth.uid() = user_id);

-- Enable Realtime
ALTER PUBLICATION supabase_realtime ADD TABLE notifications;

-- Orders: add missing columns for offer_link
ALTER TABLE orders
  ADD COLUMN IF NOT EXISTS offer_link TEXT,
  ADD COLUMN IF NOT EXISTS two_fa_link TEXT,
  ADD COLUMN IF NOT EXISTS activation_data JSONB,
  ADD COLUMN IF NOT EXISTS idempotency_key TEXT,
  ADD COLUMN IF NOT EXISTS provider_request_id TEXT,
  ADD COLUMN IF NOT EXISTS safe_error_code TEXT,
  ADD COLUMN IF NOT EXISTS safe_error_message TEXT,
  ADD COLUMN IF NOT EXISTS webhook_received_at TIMESTAMPTZ;

-- Unique idempotency key per order
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_idempotency ON orders(idempotency_key) WHERE idempotency_key IS NOT NULL;
