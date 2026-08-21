
-- =====================================================================
-- Phase-3: order_events table — تتبع كامل لمسار الطلب بـ trace_id
-- =====================================================================

CREATE TABLE IF NOT EXISTS order_events (
  id              BIGSERIAL PRIMARY KEY,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  order_id        UUID REFERENCES payment_orders(id) ON DELETE SET NULL,
  order_number    BIGINT,
  trace_id        TEXT,
  user_id         UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  device_id       TEXT,
  event_type      TEXT NOT NULL,    -- ORDER_CREATED, SCAN_STARTED, SMS_MATCH_FOUND …
  status          TEXT,             -- ok / REJECT / pending
  result          TEXT,             -- confirmed / rejected / duplicate / not_found …
  reason          TEXT,             -- human-readable error reason
  error_code      TEXT,             -- machine-readable code e.g. DUPLICATE / AMOUNT_MISMATCH
  duration_ms     INTEGER,
  retry_count     SMALLINT DEFAULT 0,
  actor           TEXT DEFAULT 'system',   -- system / admin / device:<id>
  metadata        JSONB DEFAULT '{}',
  -- لا نُسجّل: password / OTP / card numbers / raw SMS body
  CONSTRAINT order_events_event_type_not_empty CHECK (length(event_type) > 0)
);

-- index على order_id + trace_id للبحث السريع
CREATE INDEX IF NOT EXISTS idx_order_events_order_id   ON order_events(order_id);
CREATE INDEX IF NOT EXISTS idx_order_events_trace_id   ON order_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_order_events_created_at ON order_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_events_event_type ON order_events(event_type);

-- RLS: service-role فقط يكتب — admin يقرأ
ALTER TABLE order_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "service_role_all_order_events"   ON order_events;
DROP POLICY IF EXISTS "admin_read_order_events"         ON order_events;

CREATE POLICY "service_role_all_order_events" ON order_events
  FOR ALL USING (auth.role() = 'service_role');

CREATE POLICY "admin_read_order_events" ON order_events
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM profiles
      WHERE profiles.id = auth.uid()
        AND profiles.role = 'admin'
    )
  );

-- =====================================================================
-- financial_audit_log — إن لم يكن موجوداً
-- =====================================================================

CREATE TABLE IF NOT EXISTS financial_audit_log (
  id              BIGSERIAL PRIMARY KEY,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  event_type      TEXT NOT NULL,
  order_id        UUID,
  transaction_id  TEXT,
  actor           TEXT,
  amount          NUMERIC(12,2),
  decision        TEXT,          -- confirmed / rejected / manual_review
  reason          TEXT,
  trace_id        TEXT,
  metadata        JSONB DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_fin_audit_order_id   ON financial_audit_log(order_id);
CREATE INDEX IF NOT EXISTS idx_fin_audit_created_at ON financial_audit_log(created_at DESC);

ALTER TABLE financial_audit_log ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "service_role_all_fin_audit"   ON financial_audit_log;
DROP POLICY IF EXISTS "admin_read_fin_audit"         ON financial_audit_log;

CREATE POLICY "service_role_all_fin_audit" ON financial_audit_log
  FOR ALL USING (auth.role() = 'service_role');

CREATE POLICY "admin_read_fin_audit" ON financial_audit_log
  FOR SELECT USING (
    EXISTS (
      SELECT 1 FROM profiles
      WHERE profiles.id = auth.uid()
        AND profiles.role = 'admin'
    )
  );

-- =====================================================================
-- Helper: log_order_event — تسهيل الإدراج من Edge Functions
-- =====================================================================

CREATE OR REPLACE FUNCTION log_order_event(
  p_order_id     UUID,
  p_order_number BIGINT,
  p_trace_id     TEXT,
  p_user_id      UUID,
  p_device_id    TEXT,
  p_event_type   TEXT,
  p_status       TEXT DEFAULT NULL,
  p_result       TEXT DEFAULT NULL,
  p_reason       TEXT DEFAULT NULL,
  p_error_code   TEXT DEFAULT NULL,
  p_duration_ms  INTEGER DEFAULT NULL,
  p_retry_count  SMALLINT DEFAULT 0,
  p_actor        TEXT DEFAULT 'system',
  p_metadata     JSONB DEFAULT '{}'
) RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id BIGINT;
BEGIN
  INSERT INTO order_events (
    order_id, order_number, trace_id, user_id, device_id,
    event_type, status, result, reason, error_code,
    duration_ms, retry_count, actor, metadata
  ) VALUES (
    p_order_id, p_order_number, p_trace_id, p_user_id, p_device_id,
    p_event_type, p_status, p_result, p_reason, p_error_code,
    p_duration_ms, p_retry_count, p_actor, p_metadata
  ) RETURNING id INTO v_id;
  RETURN v_id;
END;
$$;
