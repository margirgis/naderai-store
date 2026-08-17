import React, { useCallback, useEffect, useState } from 'react';
import {
  Plus, Pencil, Trash2, Loader2, Tag, Clock, ToggleLeft, ToggleRight,
  PackageOpen, Star, Zap,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { toast } from 'sonner';

interface CreditPackage {
  id: string;
  name: string;
  credits: number;
  price_per_credit: number;
  original_price_per_credit: number;
  discount_percent: number;
  total_price: number;
  expires_at: string | null;
  is_active: boolean;
  sort_order: number;
  badge_text: string | null;
  created_at: string;
}

const EMPTY_FORM = {
  name: '',
  credits: '',
  price_per_credit: '',
  original_price_per_credit: '300',
  expires_at: '',
  is_active: true,
  sort_order: '0',
  badge_text: '',
};

export default function AdminPackagesPage() {
  const [packages, setPackages] = useState<CreditPackage[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [editing, setEditing] = useState<CreditPackage | null>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    const { data } = await supabase
      .from('credit_packages')
      .select('*')
      .order('sort_order')
      .order('created_at');
    setPackages((data ?? []) as CreditPackage[]);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // حساب الخصم في الواجهة للمعاينة
  const previewDiscount = (() => {
    const p = parseFloat(form.price_per_credit);
    const o = parseFloat(form.original_price_per_credit);
    if (p > 0 && o > 0) return Math.round((1 - p / o) * 100);
    return 0;
  })();

  const openCreate = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setDialogOpen(true);
  };

  const openEdit = (pkg: CreditPackage) => {
    setEditing(pkg);
    setForm({
      name: pkg.name,
      credits: String(pkg.credits),
      price_per_credit: String(pkg.price_per_credit),
      original_price_per_credit: String(pkg.original_price_per_credit),
      expires_at: pkg.expires_at ? pkg.expires_at.slice(0, 16) : '',
      is_active: pkg.is_active,
      sort_order: String(pkg.sort_order),
      badge_text: pkg.badge_text ?? '',
    });
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!form.name.trim() || !form.credits || !form.price_per_credit) {
      toast.error('يرجى ملء الاسم والكريدت والسعر');
      return;
    }
    setSaving(true);
    const payload = {
      name: form.name.trim(),
      credits: parseInt(form.credits),
      price_per_credit: parseFloat(form.price_per_credit),
      original_price_per_credit: parseFloat(form.original_price_per_credit) || 300,
      expires_at: form.expires_at ? new Date(form.expires_at).toISOString() : null,
      is_active: form.is_active,
      sort_order: parseInt(form.sort_order) || 0,
      badge_text: form.badge_text.trim() || null,
    };

    const { error } = editing
      ? await supabase.from('credit_packages').update(payload).eq('id', editing.id)
      : await supabase.from('credit_packages').insert(payload);

    if (error) { toast.error('حدث خطأ: ' + error.message); }
    else {
      toast.success(editing ? 'تم تحديث العرض' : 'تم إنشاء العرض');
      setDialogOpen(false);
      load();
    }
    setSaving(false);
  };

  const handleToggleActive = async (pkg: CreditPackage) => {
    const { error } = await supabase
      .from('credit_packages')
      .update({ is_active: !pkg.is_active })
      .eq('id', pkg.id);
    if (error) toast.error('فشل التحديث');
    else {
      toast.success(pkg.is_active ? 'تم تعطيل العرض' : 'تم تفعيل العرض');
      load();
    }
  };

  const handleDelete = async () => {
    if (!deleteId) return;
    const { error } = await supabase.from('credit_packages').delete().eq('id', deleteId);
    if (error) toast.error('فشل الحذف: ' + error.message);
    else { toast.success('تم الحذف'); load(); }
    setDeleteId(null);
  };

  const isExpired = (pkg: CreditPackage) =>
    pkg.expires_at ? new Date(pkg.expires_at) < new Date() : false;

  return (
    <AdminLayout>
      <div className="p-4 md:p-6 space-y-6">
        {/* رأس الصفحة */}
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-xl md:text-2xl font-bold text-foreground flex items-center gap-2">
              <PackageOpen className="w-6 h-6 text-primary" />
              عروض شحن الكريدت
            </h1>
            <p className="text-sm text-muted-foreground mt-1">
              إدارة باقات الكريدت والخصومات المتاحة للعملاء
            </p>
          </div>
          <Button onClick={openCreate} className="gap-2 shrink-0">
            <Plus className="w-4 h-4" />
            <span className="hidden sm:inline">عرض جديد</span>
          </Button>
        </div>

        {/* إحصائيات سريعة */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {[
            { label: 'إجمالي العروض', value: packages.length, icon: Tag, color: 'text-primary' },
            { label: 'نشط', value: packages.filter(p => p.is_active && !isExpired(p)).length, icon: ToggleRight, color: 'text-green-500' },
            { label: 'معطل / منتهي', value: packages.filter(p => !p.is_active || isExpired(p)).length, icon: ToggleLeft, color: 'text-muted-foreground' },
            { label: 'أعلى خصم', value: packages.length ? `${Math.max(...packages.map(p => Number(p.discount_percent ?? 0)))}%` : '0%', icon: Zap, color: 'text-yellow-500' },
          ].map(({ label, value, icon: Icon, color }) => (
            <Card key={label}>
              <CardContent className="p-4 flex items-center gap-3">
                <Icon className={`w-5 h-5 shrink-0 ${color}`} />
                <div className="min-w-0">
                  <p className="text-xs text-muted-foreground truncate">{label}</p>
                  <p className="text-lg font-bold text-foreground">{value}</p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

        {/* قائمة العروض */}
        {loading ? (
          <div className="flex justify-center py-12">
            <Loader2 className="w-7 h-7 animate-spin text-muted-foreground" />
          </div>
        ) : packages.length === 0 ? (
          <Card>
            <CardContent className="py-16 flex flex-col items-center gap-3 text-muted-foreground">
              <PackageOpen className="w-12 h-12" />
              <p className="text-base font-medium">لا توجد عروض بعد</p>
              <Button onClick={openCreate} variant="outline" className="gap-2 mt-2">
                <Plus className="w-4 h-4" /> أنشئ أول عرض
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {packages.map(pkg => {
              const expired = isExpired(pkg);
              const inactive = !pkg.is_active || expired;
              return (
                <Card key={pkg.id} className={`relative overflow-hidden transition-all ${inactive ? 'opacity-60' : ''}`}>
                  {pkg.badge_text && (
                    <div className="absolute top-3 left-3 z-10">
                      <Badge className="gap-1 bg-primary text-primary-foreground">
                        <Star className="w-3 h-3" />
                        {pkg.badge_text}
                      </Badge>
                    </div>
                  )}
                  <CardHeader className="pb-2 pt-4">
                    <div className="flex items-start justify-between gap-2">
                      <CardTitle className="text-base leading-tight text-balance">{pkg.name}</CardTitle>
                      <div className="flex items-center gap-1 shrink-0">
                        <Switch
                          checked={pkg.is_active && !expired}
                          onCheckedChange={() => handleToggleActive(pkg)}
                          disabled={expired}
                        />
                      </div>
                    </div>
                    {expired && (
                      <Badge variant="destructive" className="w-fit gap-1 text-xs">
                        <Clock className="w-3 h-3" /> منتهي
                      </Badge>
                    )}
                  </CardHeader>
                  <CardContent className="space-y-3">
                    {/* السعر والكريدت */}
                    <div className="bg-muted rounded-lg p-3 text-center">
                      <p className="text-2xl font-bold text-foreground">
                        {pkg.credits} <span className="text-sm font-normal text-muted-foreground">كريدت</span>
                      </p>
                      <p className="text-lg font-semibold text-primary mt-1">
                        {pkg.total_price} جنيه
                      </p>
                      <div className="flex items-center justify-center gap-2 mt-1">
                        <span className="text-xs text-muted-foreground line-through">
                          {pkg.original_price_per_credit * pkg.credits} جنيه
                        </span>
                        {Number(pkg.discount_percent) > 0 && (
                          <Badge variant="secondary" className="text-xs bg-green-500/10 text-green-600 border-green-200">
                            خصم {pkg.discount_percent}%
                          </Badge>
                        )}
                      </div>
                    </div>

                    {/* تفاصيل */}
                    <div className="grid grid-cols-2 gap-2 text-xs text-muted-foreground">
                      <div>
                        <span>سعر الكريدت:</span>
                        <span className="font-medium text-foreground mr-1">{pkg.price_per_credit} جنيه</span>
                      </div>
                      <div>
                        <span>الترتيب:</span>
                        <span className="font-medium text-foreground mr-1">#{pkg.sort_order}</span>
                      </div>
                    </div>

                    {pkg.expires_at && (
                      <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <Clock className="w-3.5 h-3.5 shrink-0" />
                        <span>ينتهي: {new Date(pkg.expires_at).toLocaleString('ar-EG')}</span>
                      </div>
                    )}

                    {/* أزرار */}
                    <div className="flex gap-2 pt-1">
                      <Button
                        variant="outline"
                        size="sm"
                        className="flex-1 gap-1.5"
                        onClick={() => openEdit(pkg)}
                      >
                        <Pencil className="w-3.5 h-3.5" />
                        تعديل
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                        onClick={() => setDeleteId(pkg.id)}
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              );
            })}
          </div>
        )}
      </div>

      {/* حوار الإنشاء/التعديل */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-w-[calc(100%-2rem)] md:max-w-lg">
          <DialogHeader>
            <DialogTitle>{editing ? 'تعديل العرض' : 'إنشاء عرض جديد'}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-1.5">
              <Label>اسم العرض</Label>
              <Input
                placeholder="مثال: باقة 25 كريدت"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>عدد الكريدت</Label>
                <Input
                  type="number" min="1"
                  placeholder="مثال: 25"
                  value={form.credits}
                  onChange={e => setForm(f => ({ ...f, credits: e.target.value }))}
                />
              </div>
              <div className="space-y-1.5">
                <Label>السعر الأصلي للكريدت</Label>
                <Input
                  type="number" min="1"
                  placeholder="300"
                  value={form.original_price_per_credit}
                  onChange={e => setForm(f => ({ ...f, original_price_per_credit: e.target.value }))}
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label>سعر الكريدت بعد الخصم (جنيه)</Label>
              <Input
                type="number" min="1"
                placeholder="مثال: 200"
                value={form.price_per_credit}
                onChange={e => setForm(f => ({ ...f, price_per_credit: e.target.value }))}
              />
              {previewDiscount > 0 && (
                <p className="text-xs text-green-600">
                  نسبة الخصم: {previewDiscount}% · الإجمالي:{' '}
                  {(parseFloat(form.credits || '0') * parseFloat(form.price_per_credit || '0')).toFixed(0)} جنيه
                </p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>نص البادج (اختياري)</Label>
                <Input
                  placeholder="مثال: الأشهر"
                  value={form.badge_text}
                  onChange={e => setForm(f => ({ ...f, badge_text: e.target.value }))}
                />
              </div>
              <div className="space-y-1.5">
                <Label>الترتيب</Label>
                <Input
                  type="number" min="0"
                  value={form.sort_order}
                  onChange={e => setForm(f => ({ ...f, sort_order: e.target.value }))}
                />
              </div>
            </div>
            <div className="space-y-1.5">
              <Label>تاريخ انتهاء العرض (اختياري)</Label>
              <Input
                type="datetime-local"
                value={form.expires_at}
                onChange={e => setForm(f => ({ ...f, expires_at: e.target.value }))}
              />
              <p className="text-xs text-muted-foreground">اتركه فارغاً للعرض الدائم</p>
            </div>
            <div className="flex items-center gap-3">
              <Switch
                checked={form.is_active}
                onCheckedChange={v => setForm(f => ({ ...f, is_active: v }))}
              />
              <Label>العرض نشط ومرئي للعملاء</Label>
            </div>
          </div>
          <DialogFooter className="gap-2">
            <Button variant="outline" onClick={() => setDialogOpen(false)}>إلغاء</Button>
            <Button onClick={handleSave} disabled={saving} className="gap-2">
              {saving && <Loader2 className="w-4 h-4 animate-spin" />}
              {editing ? 'حفظ التعديلات' : 'إنشاء العرض'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* حوار تأكيد الحذف */}
      <AlertDialog open={!!deleteId} onOpenChange={v => !v && setDeleteId(null)}>
        <AlertDialogContent className="max-w-[calc(100%-2rem)] md:max-w-lg">
          <AlertDialogHeader>
            <AlertDialogTitle>حذف العرض</AlertDialogTitle>
            <AlertDialogDescription>
              هل أنت متأكد من حذف هذا العرض؟ لا يمكن التراجع عن هذا الإجراء.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>إلغاء</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className="bg-destructive hover:bg-destructive/90">
              حذف
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </AdminLayout>
  );
}
