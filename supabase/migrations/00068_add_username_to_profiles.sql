
-- أضف عمود username إلى profiles
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS username TEXT;

-- index فريد لمنع التكرار
CREATE UNIQUE INDEX IF NOT EXISTS profiles_username_unique ON profiles (username) WHERE username IS NOT NULL;

-- أضف full_name إن لم يكن موجوداً (كان في auth metadata فقط)
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS full_name TEXT;

-- حماية role الأدمن: لا يمكن تغيير role من admin إلى user عبر update عادي
-- نصنع function تحمي الـ role
CREATE OR REPLACE FUNCTION protect_admin_role()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  -- إذا كان الدور الحالي admin، لا يُسمح بتغييره إلا لـ service_role
  IF OLD.role = 'admin' AND NEW.role != 'admin' THEN
    RAISE EXCEPTION 'لا يمكن تغيير دور المسؤول';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS protect_admin_role_trigger ON profiles;
CREATE TRIGGER protect_admin_role_trigger
  BEFORE UPDATE ON profiles
  FOR EACH ROW EXECUTE FUNCTION protect_admin_role();
