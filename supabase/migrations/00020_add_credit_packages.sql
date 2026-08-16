
-- جدول العروض/الباقات
CREATE TABLE credit_packages (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name          text NOT NULL,
  credits       integer NOT NULL CHECK (credits > 0),
  price_per_credit numeric(10,2) NOT NULL,  -- السعر الفعلي للكريدت بعد الخصم
  original_price_per_credit numeric(10,2) NOT NULL DEFAULT 300,  -- السعر الأصلي
  discount_percent numeric(5,2) GENERATED ALWAYS AS (
    ROUND((1 - price_per_credit / NULLIF(original_price_per_credit,0)) * 100, 2)
  ) STORED,
  total_price   numeric(10,2) GENERATED ALWAYS AS (
    ROUND(credits * price_per_credit, 2)
  ) STORED,
  expires_at    timestamptz,           -- وقت انتهاء العرض (NULL = دائم)
  is_active     boolean NOT NULL DEFAULT true,
  sort_order    integer NOT NULL DEFAULT 0,
  badge_text    text,                   -- نص بادج مثل "الأشهر" أو "وفر أكثر"
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- تفعيل RLS
ALTER TABLE credit_packages ENABLE ROW LEVEL SECURITY;

-- الأدمن: كل العمليات
CREATE POLICY "admin_all_packages" ON credit_packages
  FOR ALL TO authenticated
  USING (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'))
  WITH CHECK (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin'));

-- العملاء والزوار: قراءة العروض النشطة فقط
CREATE POLICY "public_read_active_packages" ON credit_packages
  FOR SELECT
  USING (is_active = true AND (expires_at IS NULL OR expires_at > now()));

-- تحديث updated_at تلقائياً
CREATE OR REPLACE FUNCTION update_credit_packages_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_packages_updated_at
  BEFORE UPDATE ON credit_packages
  FOR EACH ROW EXECUTE FUNCTION update_credit_packages_updated_at();

-- حقل package_id في طلبات الشحن لربط الطلب بالعرض
ALTER TABLE wallet_topup_requests
  ADD COLUMN IF NOT EXISTS package_id uuid REFERENCES credit_packages(id);

-- بيانات أولية
INSERT INTO credit_packages (name, credits, price_per_credit, original_price_per_credit, expires_at, sort_order, badge_text) VALUES
  ('باقة 10 كريدت', 10, 250, 300, NULL, 1, NULL),
  ('باقة 25 كريدت', 25, 200, 300, NULL, 2, 'الأشهر'),
  ('باقة 50 كريدت', 50, 150, 300, NULL, 3, 'وفر أكثر');
