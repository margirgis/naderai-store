import React, { useState } from 'react';
import { User, Loader2, KeyRound } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { useAuth } from '@/contexts/AuthContext';
import { supabase } from '@/db/supabase';
import { toast } from 'sonner';

export default function CustomerProfilePage() {
  const { profile, user } = useAuth();
  const [pwForm, setPwForm] = useState({ current: '', newPw: '', confirm: '' });
  const [pwLoading, setPwLoading] = useState(false);

  const handlePwChange = async (e: React.FormEvent) => {
    e.preventDefault();
    if (pwForm.newPw !== pwForm.confirm) { toast.error('كلمتا المرور غير متطابقتين'); return; }
    if (pwForm.newPw.length < 8) { toast.error('كلمة المرور يجب أن تكون 8 أحرف على الأقل'); return; }
    setPwLoading(true);
    try {
      const { error } = await supabase.auth.updateUser({ password: pwForm.newPw });
      if (error) { toast.error('فشل تغيير كلمة المرور'); return; }
      toast.success('تم تغيير كلمة المرور بنجاح');
      setPwForm({ current: '', newPw: '', confirm: '' });
    } finally {
      setPwLoading(false);
    }
  };

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-md mx-auto space-y-6">
        <div className="space-y-1">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <User className="w-5 h-5 text-primary" />
            حسابي
          </h1>
        </div>

        {/* Profile info */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">بيانات الحساب</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {profile?.username && (
              <div>
                <p className="text-xs text-muted-foreground mb-1">اسم المستخدم</p>
                <p className="text-sm text-foreground font-medium font-mono" dir="ltr">@{profile.username}</p>
              </div>
            )}
            {profile?.full_name && (
              <div>
                <p className="text-xs text-muted-foreground mb-1">الاسم الكامل</p>
                <p className="text-sm text-foreground font-medium">{profile.full_name}</p>
              </div>
            )}
            <div>
              <p className="text-xs text-muted-foreground mb-1">البريد الإلكتروني</p>
              <p className="text-sm text-foreground font-medium">{user?.email ?? '—'}</p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground mb-1">نوع الحساب</p>
              <p className="text-sm text-foreground">{profile?.role === 'admin' ? 'مسؤول' : 'عميل'}</p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground mb-1">حالة الحساب</p>
              <p className={`text-sm font-medium ${profile?.status === 'active' ? 'text-green-400' : 'text-destructive'}`}>
                {profile?.status === 'active' ? 'نشط' : 'موقوف'}
              </p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground mb-1">تاريخ التسجيل</p>
              <p className="text-sm text-foreground">
                {profile?.created_at ? new Date(profile.created_at).toLocaleDateString('ar-SA') : '—'}
              </p>
            </div>
          </CardContent>
        </Card>

        {/* Change password */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <KeyRound className="w-4 h-4 text-muted-foreground" />
              تغيير كلمة المرور
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handlePwChange} className="space-y-4">
              <div className="space-y-1.5">
                <Label className="text-xs text-muted-foreground">كلمة المرور الجديدة</Label>
                <Input type="password" placeholder="••••••••"
                  value={pwForm.newPw}
                  onChange={e => setPwForm(f => ({ ...f, newPw: e.target.value }))}
                  className="bg-background border-border" required />
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs text-muted-foreground">تأكيد كلمة المرور الجديدة</Label>
                <Input type="password" placeholder="••••••••"
                  value={pwForm.confirm}
                  onChange={e => setPwForm(f => ({ ...f, confirm: e.target.value }))}
                  className="bg-background border-border" required />
              </div>
              <Button type="submit" size="sm" disabled={pwLoading} className="gap-2">
                {pwLoading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                حفظ كلمة المرور
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </CustomerLayout>
  );
}
