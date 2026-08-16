-- 1. Set extract_18m as the ONE live store service (Gemini AI Pro 18M)
UPDATE provider_services SET
  store_enabled = true,
  name = 'Gemini AI Pro — 18 Months',
  description_ar = 'اشتراك Gemini AI Pro لمدة 18 شهراً — 5 تيرابايت تخزين، 1000 AI Credit شهرياً في Google Flow، وميزات Google AI الكاملة.',
  customer_price = COALESCE(customer_price, final_credit_price, 1.5)
WHERE provider_code = 'extract_18m';

-- 2. Disable all other services from store (keep in DB/Admin)
UPDATE provider_services SET store_enabled = false
WHERE provider_code != 'extract_18m';

-- 3. Add display_name_ar column if missing
ALTER TABLE provider_services
  ADD COLUMN IF NOT EXISTS display_name_ar TEXT,
  ADD COLUMN IF NOT EXISTS display_name_en TEXT;

UPDATE provider_services SET
  display_name_ar = 'جيميناي برو 18 شهر',
  display_name_en = 'Gemini AI Pro — 18 Months'
WHERE provider_code = 'extract_18m';
