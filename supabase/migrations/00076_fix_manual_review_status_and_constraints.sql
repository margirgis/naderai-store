
-- BUG-001 FIX: إضافة manual_review و amount_mismatch لـ payment_orders_status_check
ALTER TABLE payment_orders DROP CONSTRAINT IF EXISTS payment_orders_status_check;
ALTER TABLE payment_orders ADD CONSTRAINT payment_orders_status_check
  CHECK (status = ANY (ARRAY[
    'awaiting_payment','pending','scanning','confirmed','failed',
    'expired','cancelled','duplicate','reopened',
    'manual_review','amount_mismatch'  -- ✅ مضافة
  ]));

-- إصلاح أي صفوف عالقة بـ amount_mismatch لو موجودة
UPDATE payment_orders SET status = 'manual_review'
WHERE status NOT IN (
  'awaiting_payment','pending','scanning','confirmed','failed',
  'expired','cancelled','duplicate','reopened','manual_review','amount_mismatch'
);
