import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Shield, Eye, EyeOff, Loader2, UserPlus } from 'lucide-react';
import { supabase } from '@/db/supabase';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { toast } from 'sonner';

export default function RegisterPage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', username: '', email: '', password: '', confirm: '' });
  const [showPw, setShowPw] = useState(false);
  const [loading, setLoading] = useState(false);

  const update = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(f => ({ ...f, [k]: e.target.value }));

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (form.password !== form.confirm) {
      toast.error('كلمتا المرور غير متطابقتين');
      return;
    }
    if (form.password.length < 8) {
      toast.error('كلمة المرور يجب أن تكون 8 أحرف على الأقل');
      return;
    }
    if (form.username.trim().length < 3) {
      toast.error('اسم المستخدم يجب أن يكون 3 أحرف على الأقل');
      return;
    }
    setLoading(true);
    try {
      const { data, error } = await supabase.auth.signUp({
        email: form.email.trim(),
        password: form.password,
        options: { data: { full_name: form.name.trim(), username: form.username.trim().toLowerCase() } },
      });
      if (error) { toast.error(error.message); return; }
      if (data.user) {
        // إنشاء profile فقط إذا لم يكن موجوداً — لا نكتب على role الموجود أبداً
        const { data: existing } = await supabase
          .from('profiles')
          .select('id, role')
          .eq('id', data.user.id)
          .maybeSingle();

        if (!existing) {
          await supabase.from('profiles').insert({
            id: data.user.id,
            email: form.email.trim(),
            full_name: form.name.trim() || null,
            username: form.username.trim().toLowerCase(),
            role: 'user',
            wallet_balance: 0,
            status: 'active',
          });
        } else if (!existing.role || existing.role === 'user') {
          // فقط أحدّث البيانات غير الحساسة — لا نلمس role أبداً
          await supabase.from('profiles').update({
            email: form.email.trim(),
            full_name: form.name.trim() || null,
            username: form.username.trim().toLowerCase(),
          }).eq('id', data.user.id);
        }
        toast.success('تم إنشاء حسابك بنجاح! مرحباً بك');
        navigate('/store', { replace: true });
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen w-full bg-background flex items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm space-y-8">
        <div className="text-center space-y-2">
          <div className="w-10 h-10 rounded bg-primary/10 border border-primary/20 flex items-center justify-center mx-auto">
            <UserPlus className="w-5 h-5 text-primary" />
          </div>
          <h1 className="text-xl font-bold text-foreground">إنشاء حساب جديد</h1>
          <p className="text-sm text-muted-foreground">انضم إلى Nader AI واطلب خدماتك</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="name" className="text-xs text-muted-foreground">الاسم الكامل</Label>
            <Input id="name" placeholder="اسمك الكامل" value={form.name}
              onChange={update('name')} required className="bg-card border-border" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="username" className="text-xs text-muted-foreground">اسم المستخدم</Label>
            <Input id="username" placeholder="مثال: ahmed99" value={form.username}
              onChange={update('username')} required minLength={3}
              className="bg-card border-border" dir="ltr" />
            <p className="text-[11px] text-muted-foreground">لا يقبل مسافات، يُستخدم لتسجيل الدخول</p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="email" className="text-xs text-muted-foreground">البريد الإلكتروني</Label>
            <Input id="email" type="email" placeholder="example@email.com" value={form.email}
              onChange={update('email')} required className="bg-card border-border" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="password" className="text-xs text-muted-foreground">كلمة المرور</Label>
            <div className="relative">
              <Input id="password" type={showPw ? 'text' : 'password'} placeholder="••••••••"
                value={form.password} onChange={update('password')} required
                className="bg-card border-border ps-10" />
              <button type="button" onClick={() => setShowPw(!showPw)}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground" tabIndex={-1}>
                {showPw ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="confirm" className="text-xs text-muted-foreground">تأكيد كلمة المرور</Label>
            <Input id="confirm" type="password" placeholder="••••••••"
              value={form.confirm} onChange={update('confirm')} required className="bg-card border-border" />
          </div>
          <Button type="submit" className="w-full" disabled={loading}>
            {loading && <Loader2 className="w-4 h-4 ms-2 animate-spin" />}
            {loading ? 'جارٍ الإنشاء…' : 'إنشاء الحساب'}
          </Button>
        </form>

        <p className="text-xs text-center text-muted-foreground">
          لديك حساب بالفعل؟{' '}
          <Link to="/login" className="text-primary hover:underline">تسجيل الدخول</Link>
        </p>
      </div>
    </div>
  );
}
