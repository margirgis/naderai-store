
-- إضافة حقل metadata لتمييز طلبات الاختبار التي يُنشئها الأدمن
ALTER TABLE payment_orders
  ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT NULL;

-- إضافة حقل is_test_order كـ generated column منطقي للفلترة السريعة
ALTER TABLE payment_orders
  ADD COLUMN IF NOT EXISTS is_test_order BOOLEAN NOT NULL GENERATED ALWAYS AS (
    COALESCE((metadata->>'is_test_order')::BOOLEAN, FALSE)
  ) STORED;

CREATE INDEX IF NOT EXISTS payment_orders_is_test_idx ON payment_orders(is_test_order);
