
-- ═══════════════════════════════════════════════════════════════
-- Migration 028: Secure Payment Order Flow
-- - payment_orders table (server-side fingerprint, expires_at)
-- - per-user fingerprint ring of 99 slots (no repeat within 99)
-- - pg_cron auto-expire job
-- - create_payment_order RPC (idempotency + single-active rule)
-- ═══════════════════════════════════════════════════════════════

-- 1. payment_orders ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_orders (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  order_number      BIGSERIAL,                              -- visible to user
  user_id           UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  offer_id          UUID        REFERENCES credit_packages(id) ON DELETE SET NULL,
  credits_qty       INT         NOT NULL CHECK (credits_qty >= 1),
  base_amount       NUMERIC(12,2) NOT NULL,
  discount_amount   NUMERIC(12,2) NOT NULL DEFAULT 0,
  fingerprint       NUMERIC(10,2) NOT NULL,                 -- server-generated only
  expected_amount   NUMERIC(12,2) NOT NULL,                 -- base_amount + fingerprint
  -- status values: pending | awaiting_payment | scanning | confirmed | failed |
  --                cancelled | expired | duplicate | not_found | amount_mismatch
  status            TEXT        NOT NULL DEFAULT 'awaiting_payment',
  sender_phone      TEXT,
  sender_name       TEXT,
  idempotency_key   TEXT,                                   -- client-side per-request key
  expires_at        TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '15 minutes'),
  confirmed_at      TIMESTAMPTZ,
  cancelled_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Unique order_number
CREATE UNIQUE INDEX IF NOT EXISTS payment_orders_order_number_idx ON payment_orders(order_number);

-- Idempotency: same client key → same order
CREATE UNIQUE INDEX IF NOT EXISTS payment_orders_idempotency_idx
  ON payment_orders(user_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

-- Fast lookup: active orders per user
CREATE INDEX IF NOT EXISTS payment_orders_user_status_idx ON payment_orders(user_id, status);

-- Fast lookup by id
CREATE INDEX IF NOT EXISTS payment_orders_id_created_idx ON payment_orders(id, created_at DESC);

-- 2. fingerprint_ring ─────────────────────────────────────────
-- Tracks last 99 fingerprint values per user to ensure no repeat
CREATE TABLE IF NOT EXISTS user_fingerprint_ring (
  id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  fingerprint NUMERIC(10,2) NOT NULL,
  used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id, fingerprint)  -- within the ring
);
CREATE INDEX IF NOT EXISTS fingerprint_ring_user_idx ON user_fingerprint_ring(user_id, used_at DESC);

-- 3. auto updated_at trigger ──────────────────────────────────
CREATE OR REPLACE FUNCTION set_payment_orders_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$;
DROP TRIGGER IF EXISTS payment_orders_updated_at ON payment_orders;
CREATE TRIGGER payment_orders_updated_at
  BEFORE UPDATE ON payment_orders
  FOR EACH ROW EXECUTE FUNCTION set_payment_orders_updated_at();

-- 4. RLS ──────────────────────────────────────────────────────
ALTER TABLE payment_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_fingerprint_ring ENABLE ROW LEVEL SECURITY;

-- Users see/manage only their own orders
DROP POLICY IF EXISTS "user_own_payment_orders_select" ON payment_orders;
CREATE POLICY "user_own_payment_orders_select" ON payment_orders
  FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "user_own_payment_orders_insert" ON payment_orders;
CREATE POLICY "user_own_payment_orders_insert" ON payment_orders
  FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "user_own_payment_orders_update" ON payment_orders;
CREATE POLICY "user_own_payment_orders_update" ON payment_orders
  FOR UPDATE USING (auth.uid() = user_id);

-- Admins full access
DROP POLICY IF EXISTS "admin_all_payment_orders" ON payment_orders;
CREATE POLICY "admin_all_payment_orders" ON payment_orders
  FOR ALL USING (
    EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin')
  );

-- Fingerprint ring: SECURITY DEFINER access only (no direct user policy needed)
DROP POLICY IF EXISTS "no_direct_access_fingerprint_ring" ON user_fingerprint_ring;
CREATE POLICY "no_direct_access_fingerprint_ring" ON user_fingerprint_ring
  FOR ALL USING (
    EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin')
  );

-- 5. create_payment_order RPC ─────────────────────────────────
CREATE OR REPLACE FUNCTION create_payment_order(
  p_user_id        UUID,
  p_credits_qty    INT,
  p_offer_id       UUID        DEFAULT NULL,
  p_idempotency_key TEXT       DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_existing      payment_orders%ROWTYPE;
  v_package       credit_packages%ROWTYPE;
  v_price_per     NUMERIC(12,2);
  v_base_amount   NUMERIC(12,2);
  v_discount_amt  NUMERIC(12,2) := 0;
  v_fingerprint   NUMERIC(10,2);
  v_expected      NUMERIC(12,2);
  v_order         payment_orders%ROWTYPE;
  v_ring_count    INT;
  v_candidate     NUMERIC(10,2);
  v_attempt       INT := 0;
  DEFAULT_PRICE   CONSTANT NUMERIC := 300;
BEGIN
  -- ① Idempotency: return existing order if same key was already used
  IF p_idempotency_key IS NOT NULL THEN
    SELECT * INTO v_existing
    FROM payment_orders
    WHERE user_id = p_user_id
      AND idempotency_key = p_idempotency_key
    LIMIT 1;
    IF FOUND THEN
      RETURN jsonb_build_object(
        'ok', true,
        'idempotent', true,
        'order_id', v_existing.id,
        'order_number', v_existing.order_number,
        'status', v_existing.status,
        'expected_amount', v_existing.expected_amount,
        'fingerprint', v_existing.fingerprint,
        'expires_at', v_existing.expires_at,
        'credits_qty', v_existing.credits_qty
      );
    END IF;
  END IF;

  -- ② Single-active-order rule: check for existing active order
  SELECT * INTO v_existing
  FROM payment_orders
  WHERE user_id = p_user_id
    AND status IN ('awaiting_payment', 'pending', 'scanning')
    AND expires_at > now()
  ORDER BY created_at DESC
  LIMIT 1;

  IF FOUND THEN
    RETURN jsonb_build_object(
      'ok', true,
      'has_active', true,
      'order_id', v_existing.id,
      'order_number', v_existing.order_number,
      'status', v_existing.status,
      'expected_amount', v_existing.expected_amount,
      'fingerprint', v_existing.fingerprint,
      'expires_at', v_existing.expires_at,
      'credits_qty', v_existing.credits_qty,
      'base_amount', v_existing.base_amount,
      'discount_amount', v_existing.discount_amount,
      'offer_id', v_existing.offer_id
    );
  END IF;

  -- ③ Resolve offer pricing
  IF p_offer_id IS NOT NULL THEN
    SELECT * INTO v_package
    FROM credit_packages
    WHERE id = p_offer_id AND is_active = true
      AND (expires_at IS NULL OR expires_at > now());
    IF FOUND THEN
      v_price_per   := v_package.price_per_credit;
      v_base_amount := p_credits_qty * v_price_per;
      -- discount vs original price
      v_discount_amt := p_credits_qty *
        GREATEST(v_package.original_price_per_credit - v_package.price_per_credit, 0);
    ELSE
      -- Invalid/expired offer: fallback to default price
      v_price_per   := DEFAULT_PRICE;
      v_base_amount := p_credits_qty * v_price_per;
    END IF;
  ELSE
    v_price_per   := DEFAULT_PRICE;
    v_base_amount := p_credits_qty * v_price_per;
  END IF;

  -- ④ Generate unique server-side fingerprint (99-slot ring per user)
  SELECT COUNT(*) INTO v_ring_count
  FROM user_fingerprint_ring WHERE user_id = p_user_id;

  -- If ring is full (≥99), evict the oldest entry to make room
  IF v_ring_count >= 99 THEN
    DELETE FROM user_fingerprint_ring
    WHERE id = (
      SELECT id FROM user_fingerprint_ring
      WHERE user_id = p_user_id
      ORDER BY used_at ASC
      LIMIT 1
    );
  END IF;

  -- Try up to 200 times to generate a fingerprint not in the ring
  LOOP
    v_attempt := v_attempt + 1;
    -- Random value between 0.01 and 0.99
    v_candidate := (FLOOR(RANDOM() * 99) + 1) / 100.0;

    EXIT WHEN NOT EXISTS (
      SELECT 1 FROM user_fingerprint_ring
      WHERE user_id = p_user_id AND fingerprint = v_candidate
    );

    IF v_attempt > 200 THEN
      -- Fallback: use random 3-decimal which virtually never collides
      v_candidate := ROUND((RANDOM() * 0.98 + 0.01)::NUMERIC, 3);
      EXIT;
    END IF;
  END LOOP;

  v_fingerprint := v_candidate;
  v_expected    := ROUND(v_base_amount + v_fingerprint, 2);

  -- ⑤ Insert order
  INSERT INTO payment_orders (
    user_id, offer_id, credits_qty,
    base_amount, discount_amount,
    fingerprint, expected_amount,
    status, idempotency_key,
    expires_at
  ) VALUES (
    p_user_id,
    p_offer_id,
    p_credits_qty,
    v_base_amount,
    v_discount_amt,
    v_fingerprint,
    v_expected,
    'awaiting_payment',
    p_idempotency_key,
    now() + INTERVAL '15 minutes'
  )
  RETURNING * INTO v_order;

  -- ⑥ Record fingerprint in ring
  INSERT INTO user_fingerprint_ring(user_id, fingerprint)
  VALUES (p_user_id, v_fingerprint)
  ON CONFLICT (user_id, fingerprint) DO UPDATE SET used_at = now();

  RETURN jsonb_build_object(
    'ok', true,
    'order_id', v_order.id,
    'order_number', v_order.order_number,
    'status', v_order.status,
    'credits_qty', v_order.credits_qty,
    'base_amount', v_order.base_amount,
    'discount_amount', v_order.discount_amount,
    'fingerprint', v_order.fingerprint,
    'expected_amount', v_order.expected_amount,
    'expires_at', v_order.expires_at,
    'offer_id', v_order.offer_id
  );
END;
$$;

-- 6. cancel_payment_order RPC ─────────────────────────────────
CREATE OR REPLACE FUNCTION cancel_payment_order(
  p_order_id UUID,
  p_user_id  UUID
)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_order payment_orders%ROWTYPE;
BEGIN
  SELECT * INTO v_order FROM payment_orders
  WHERE id = p_order_id AND user_id = p_user_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;
  IF v_order.status NOT IN ('awaiting_payment', 'pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'cannot_cancel_terminal_status',
      'status', v_order.status);
  END IF;
  UPDATE payment_orders
  SET status = 'cancelled', cancelled_at = now()
  WHERE id = p_order_id;
  RETURN jsonb_build_object('ok', true, 'order_id', p_order_id, 'status', 'cancelled');
END;
$$;

-- 7. get_active_payment_order RPC ─────────────────────────────
CREATE OR REPLACE FUNCTION get_active_payment_order(p_user_id UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_order payment_orders%ROWTYPE;
BEGIN
  SELECT * INTO v_order
  FROM payment_orders
  WHERE user_id = p_user_id
    AND status IN ('awaiting_payment', 'pending', 'scanning')
    AND expires_at > now()
  ORDER BY created_at DESC
  LIMIT 1;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', true, 'has_active', false);
  END IF;
  RETURN jsonb_build_object(
    'ok', true, 'has_active', true,
    'order_id', v_order.id,
    'order_number', v_order.order_number,
    'status', v_order.status,
    'credits_qty', v_order.credits_qty,
    'base_amount', v_order.base_amount,
    'discount_amount', v_order.discount_amount,
    'fingerprint', v_order.fingerprint,
    'expected_amount', v_order.expected_amount,
    'expires_at', v_order.expires_at,
    'offer_id', v_order.offer_id,
    'sender_phone', v_order.sender_phone,
    'sender_name', v_order.sender_name
  );
END;
$$;

-- 8. submit_payment_details RPC ───────────────────────────────
-- User submits sender_phone + sender_name → linked to wallet_topup_request
CREATE OR REPLACE FUNCTION submit_payment_details(
  p_order_id    UUID,
  p_user_id     UUID,
  p_sender_phone TEXT,
  p_sender_name  TEXT DEFAULT NULL
)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
  v_order   payment_orders%ROWTYPE;
  v_vf_num  TEXT;
  v_topup   UUID;
BEGIN
  SELECT * INTO v_order FROM payment_orders
  WHERE id = p_order_id AND user_id = p_user_id FOR UPDATE;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;
  IF v_order.status NOT IN ('awaiting_payment') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_in_awaiting_payment',
      'status', v_order.status);
  END IF;
  IF v_order.expires_at <= now() THEN
    UPDATE payment_orders SET status = 'expired' WHERE id = p_order_id;
    RETURN jsonb_build_object('ok', false, 'reason', 'order_expired');
  END IF;

  -- Update order with sender details
  UPDATE payment_orders
  SET sender_phone = p_sender_phone,
      sender_name  = p_sender_name,
      status       = 'scanning'
  WHERE id = p_order_id;

  -- Get Vodafone number from system settings
  SELECT value INTO v_vf_num FROM system_settings WHERE key = 'vodafone_cash_number' LIMIT 1;

  -- Insert into wallet_topup_requests (existing scanning system)
  INSERT INTO wallet_topup_requests (
    customer_id,
    amount,
    credits_requested,
    fingerprint_amount,
    sender_phone,
    sender_name,
    payment_method,
    package_id,
    notes
  ) VALUES (
    p_user_id,
    v_order.expected_amount,
    v_order.credits_qty,
    v_order.expected_amount,
    p_sender_phone,
    p_sender_name,
    'vodafone_cash',
    v_order.offer_id,
    'payment_order_id:' || p_order_id::text
  )
  RETURNING id INTO v_topup;

  RETURN jsonb_build_object(
    'ok', true,
    'order_id', p_order_id,
    'topup_request_id', v_topup,
    'status', 'scanning',
    'expected_amount', v_order.expected_amount
  );
END;
$$;

-- 9. pg_cron: auto-expire payment_orders every minute ─────────
CREATE EXTENSION IF NOT EXISTS pg_cron;

CREATE OR REPLACE FUNCTION expire_payment_orders()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  UPDATE payment_orders
  SET status = 'expired'
  WHERE status IN ('awaiting_payment', 'pending', 'scanning')
    AND expires_at <= now();
END;
$$;

SELECT cron.schedule(
  'expire-payment-orders',
  '* * * * *',
  $$ SELECT expire_payment_orders(); $$
);

-- 10. Add payment_order_id link to wallet_topup_requests ──────
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS payment_order_id UUID REFERENCES payment_orders(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS wallet_topup_requests_payment_order_idx
  ON wallet_topup_requests(payment_order_id)
  WHERE payment_order_id IS NOT NULL;

-- 11. Realtime for payment_orders ─────────────────────────────
ALTER PUBLICATION supabase_realtime ADD TABLE payment_orders;
