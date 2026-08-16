
-- ═══════════════════════════════════════════════════════════════════════
-- MIGRATION: جدول تاريخ الحالات + balance_before + سجل التدقيق للإدارة
-- ═══════════════════════════════════════════════════════════════════════

-- 1. إضافة balance_before لجدول wallet_transactions
ALTER TABLE wallet_transactions ADD COLUMN IF NOT EXISTS balance_before NUMERIC DEFAULT 0;

-- 2. جدول تاريخ حالات طلبات الشحن (order_status_history)
CREATE TABLE IF NOT EXISTS order_status_history (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id    UUID NOT NULL REFERENCES wallet_topup_requests(id) ON DELETE CASCADE,
  old_status    TEXT,
  new_status    TEXT NOT NULL,
  scan_status   TEXT,
  changed_by    UUID REFERENCES profiles(id),        -- NULL = نظام تلقائي
  changed_by_device TEXT,                            -- device_id للأجهزة
  reason        TEXT,
  metadata      JSONB,
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_status_history_request ON order_status_history(request_id);
CREATE INDEX IF NOT EXISTS idx_order_status_history_created ON order_status_history(created_at DESC);

-- RLS
ALTER TABLE order_status_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "admin_read_order_history" ON order_status_history FOR SELECT TO authenticated USING (is_admin());
CREATE POLICY "service_insert_order_history" ON order_status_history FOR INSERT TO service_role WITH CHECK (true);

-- 3. جدول سجل التدقيق الشامل (admin_audit_log)
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_id    UUID REFERENCES profiles(id),
  action      TEXT NOT NULL,                  -- 'credit', 'debit', 'status_change', 'rescan', etc.
  target_user UUID REFERENCES profiles(id),
  target_ref  TEXT,                            -- reference ID (request_id, order_id, etc.)
  amount      NUMERIC,
  balance_before NUMERIC,
  balance_after  NUMERIC,
  reason      TEXT,
  metadata    JSONB,
  ip_address  TEXT,
  created_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_admin ON admin_audit_log(admin_id);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_target ON admin_audit_log(target_user);
CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created ON admin_audit_log(created_at DESC);

ALTER TABLE admin_audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY "admin_read_audit" ON admin_audit_log FOR SELECT TO authenticated USING (is_admin());
CREATE POLICY "service_insert_audit" ON admin_audit_log FOR INSERT TO service_role WITH CHECK (true);

-- 4. دالة helper لإدراج سجل تاريخ الحالة
CREATE OR REPLACE FUNCTION insert_order_status_history(
  p_request_id UUID,
  p_old_status TEXT,
  p_new_status TEXT,
  p_scan_status TEXT DEFAULT NULL,
  p_changed_by UUID DEFAULT NULL,
  p_device_id  TEXT DEFAULT NULL,
  p_reason     TEXT DEFAULT NULL,
  p_metadata   JSONB DEFAULT NULL
) RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  INSERT INTO order_status_history(request_id, old_status, new_status, scan_status, changed_by, changed_by_device, reason, metadata)
  VALUES (p_request_id, p_old_status, p_new_status, p_scan_status, p_changed_by, p_device_id, p_reason, p_metadata);
END;
$$;
