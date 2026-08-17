
-- ═══════════════════════════════════════════════════════════════════════════
-- Migration 00040: إضافة full_name لـ profiles + backfill + trigger تلقائي
--                  + تحديث get_device_pending_tasks لإرجاع customer_name
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. إضافة حقل full_name لجدول profiles (آمن — لا يكسر الحسابات القديمة)
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS full_name TEXT;

-- 2. Backfill: استرجاع full_name من auth.users meta للمستخدمين الحاليين
UPDATE profiles p
SET full_name = u.raw_user_meta_data->>'full_name'
FROM auth.users u
WHERE u.id = p.id
  AND (u.raw_user_meta_data->>'full_name') IS NOT NULL
  AND p.full_name IS NULL;

-- 3. Trigger function: ينسخ full_name من auth.users إلى profiles عند INSERT/UPDATE
CREATE OR REPLACE FUNCTION public.sync_profile_full_name()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
BEGIN
  -- عند إنشاء مستخدم جديد أو تحديث بياناته، انسخ full_name لـ profiles
  UPDATE profiles
  SET full_name = NEW.raw_user_meta_data->>'full_name'
  WHERE id = NEW.id
    AND (NEW.raw_user_meta_data->>'full_name') IS NOT NULL;
  RETURN NEW;
END;
$$;

-- أسقط الـ trigger القديم لو موجود ثم أنشئه من جديد
DROP TRIGGER IF EXISTS trg_sync_profile_full_name ON auth.users;
CREATE TRIGGER trg_sync_profile_full_name
  AFTER INSERT OR UPDATE OF raw_user_meta_data ON auth.users
  FOR EACH ROW
  EXECUTE FUNCTION public.sync_profile_full_name();

-- 4. تحديث get_device_pending_tasks لإرجاع customer_name + request_created_at
--    وتوحيد المفتاح: يُرجع pending_tasks (بدل tasks) ليتوافق مع HeartbeatManager
CREATE OR REPLACE FUNCTION public.get_device_pending_tasks(p_device_id text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $$
DECLARE
  v_tasks JSONB;
  v_cmds  JSONB;
BEGIN
  SELECT COALESCE(jsonb_agg(t), '[]'::jsonb) INTO v_tasks
  FROM (
    SELECT
      pt.id                       AS task_id,
      pt.request_id,
      wtr.order_number,
      pt.amount_requested         AS amount,
      pt.amount_requested,
      pt.sender_phone_requested,
      pt.sender_name_requested,
      pt.fingerprint_amount,
      pt.credits_amount,
      wtr.credits_requested,
      pt.retry_count,
      pt.assigned_at,
      pt.payment_order_id,
      pt.order_expires_at,
      wtr.payment_method,
      p.phone                     AS customer_phone,
      p.email                     AS customer_email,
      -- ✅ جديد: اسم صاحب الحساب الحقيقي (ليس اسم المُحوِّل من SMS)
      COALESCE(p.full_name, split_part(p.email, '@', 1)) AS customer_name,
      -- ✅ جديد: وقت إنشاء الطلب
      wtr.created_at              AS request_created_at,
      wtr.notes
    FROM pending_tasks pt
    JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
    LEFT JOIN profiles p ON p.id = wtr.customer_id
    WHERE pt.device_id = p_device_id
      AND pt.task_status IN ('pending', 'assigned', 'scanning')
    ORDER BY pt.created_at
    LIMIT 20
  ) t;

  SELECT COALESCE(jsonb_agg(c), '[]'::jsonb) INTO v_cmds
  FROM (
    SELECT id AS command_id, command_type, payload, created_at
    FROM device_commands
    WHERE device_id = p_device_id AND status = 'pending'
    ORDER BY created_at
    LIMIT 5
  ) c;

  -- ✅ يُرجع pending_tasks (المفتاح الذي يقرأه HeartbeatManager)
  RETURN jsonb_build_object(
    'pending_tasks', v_tasks,
    'commands', v_cmds
  );
END;
$$;
