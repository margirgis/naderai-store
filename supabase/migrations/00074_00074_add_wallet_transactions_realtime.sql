
-- إضافة wallet_transactions لـ Realtime حتى يحدّث الـ website رصيد العميل فوراً
ALTER PUBLICATION supabase_realtime ADD TABLE wallet_transactions;
