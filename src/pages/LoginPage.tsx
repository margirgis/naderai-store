import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Shield, Eye, EyeOff, Loader2 } from 'lucide-react';
import { supabase } from '@/db/supabase';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';

export default function LoginPage() {
  const navigate = useNavigate();
  const [identifier, setIdentifier] = useState(''); // بريد أو اسم مستخدم
  const [password, setPassword] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!identifier.trim() || !password) return;
    setLoading(true);
    try {
      let emailToUse = identifier.trim();

      // إذا لم يكن بريداً إلكترونياً — ابحث عن الـ email عبر username
      if (!emailToUse.includes('@')) {
        const { data: profileData, error: profileError } = await supabase
          .from('profiles')
          .select('email')
          .eq('username', emailToUse.toLowerCase())
          .maybeSingle();
        if (profileError || !profileData?.email) {
          toast.error('اسم المستخدم غير موجود');
          return;
        }
        emailToUse = profileData.email;
      }

      const { data, error } = await supabase.auth.signInWithPassword({
        email: emailToUse,
        password,
      });
      if (error) {
        toast.error('فشل تسجيل الدخول. تحقق من بياناتك.');
        return;
      }
      toast.success('تم تسجيل الدخول بنجاح');
      // Redirect based on role — fetch profile to determine
      const { data: profile } = await supabase
        .from('profiles')
        .select('role')
        .eq('id', data.user!.id)
        .maybeSingle();
      if (profile?.role === 'admin') {
        navigate('/', { replace: true });
      } else {
        navigate('/store', { replace: true });
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full flex bg-background">
      {/* Brand panel */}
      <div className="hidden md:flex flex-col justify-between w-1/2 bg-card border-l border-border px-12 py-16">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded bg-primary flex items-center justify-center">
            <Shield className="w-5 h-5 text-primary-foreground" />
          </div>
          <span className="text-base font-semibold text-foreground">Nader AI</span>
        </div>

        <div className="space-y-6">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded border border-primary/30 bg-primary/10 text-xs font-medium text-primary tracking-widest">
            البيئة الحية (Live)
          </div>
          <h1 className="text-3xl font-bold text-foreground leading-tight text-balance">
            Nader AI<br />لإعادة البيع
          </h1>
          <p className="text-sm text-muted-foreground max-w-xs text-pretty">
            اطلب خدماتك بسهولة، تابع طلباتك، وأدر رصيدك — كل شيء في مكان واحد.
          </p>
        </div>

        <div className="space-y-3">
          {['متجر الخدمات', 'تتبع الطلبات', 'إدارة المحفظة', 'خدمات Live حقيقية'].map(item => (
            <div key={item} className="flex items-center gap-2 text-xs text-muted-foreground">
              <div className="w-1.5 h-1.5 rounded-full bg-primary/60 shrink-0" />
              {item}
            </div>
          ))}
        </div>
      </div>

      {/* Login form */}
      <div className="flex-1 flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm space-y-8">
          {/* Mobile brand */}
          <div className="md:hidden flex items-center gap-2">
            <div className="w-7 h-7 rounded bg-primary flex items-center justify-center">
              <Shield className="w-4 h-4 text-primary-foreground" />
            </div>
            <span className="font-semibold text-foreground">Nader AI</span>
          </div>

          <div className="space-y-1">
            <h2 className="text-xl font-bold text-foreground">تسجيل الدخول</h2>
            <p className="text-sm text-muted-foreground">للعملاء والمسؤولين</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="identifier" className="text-xs text-muted-foreground tracking-wide">البريد الإلكتروني أو اسم المستخدم</Label>
              <Input id="identifier" type="text" placeholder="example@email.com أو اسم المستخدم"
                value={identifier} onChange={e => setIdentifier(e.target.value)}
                autoComplete="username" required
                className="bg-card border-border text-foreground placeholder:text-muted-foreground" />
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="password" className="text-xs text-muted-foreground tracking-wide">كلمة المرور</Label>
              <div className="relative">
                <Input id="password" type={showPw ? 'text' : 'password'} placeholder="••••••••"
                  value={password} onChange={e => setPassword(e.target.value)}
                  autoComplete="current-password" required
                  className="bg-card border-border text-foreground ps-10" />
                <button type="button" onClick={() => setShowPw(!showPw)} tabIndex={-1}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                  {showPw ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            <Button type="submit" className="w-full" disabled={loading}>
              {loading && <Loader2 className="w-4 h-4 ms-2 animate-spin" />}
              {loading ? 'جارٍ الدخول…' : 'دخول'}
            </Button>
          </form>

          <p className="text-xs text-center text-muted-foreground">
            ليس لديك حساب؟{' '}
            <Link to="/register" className="text-primary hover:underline">إنشاء حساب جديد</Link>
          </p>

          <p className="text-xs text-muted-foreground text-center text-pretty">
            بتسجيل الدخول أنت توافق على{' '}
            <span className="underline cursor-pointer hover:text-foreground">شروط الخدمة</span>
            {' '}و{' '}
            <span className="underline cursor-pointer hover:text-foreground">سياسة الخصوصية</span>.
          </p>
        </div>
      </div>
    </div>
  );
}
