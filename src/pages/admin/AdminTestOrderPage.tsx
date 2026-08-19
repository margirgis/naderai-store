import React, { useCallback, useState } from 'react';
import {
  FlaskConical, Search, Loader2, CheckCircle2, XCircle,
  CreditCard, User, DollarSign, Hash, Clock, Info, Copy, Phone,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import {
  Alert, AlertDescription, AlertTitle,
} from '@/components/ui/alert';
import { Separator } from '@/components/ui/separator';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { toast } from 'sonner';

interface CustomerResult {
  id: string;
  email: string | null;
  phone: string | null;
  full_name: string | null;
  wallet_balance: number;
  role: string;
}

interface CreatedOrder {
  order_id: string;
  order_number: number;
  expected_amount: number;
  credits_qty: number;
  expires_at: string;
  topup_request_id: string;
  sender_phone: string;
  sender_name: string | null;
  customer_name: string | null;
  customer_email: string | null;
  vodafone_number: string | null;
}

export default function AdminTestOrderPage() {
  // ── بحث عن العميل ─────────────────────────────────────────
  const [searchQuery, setSearchQuery] = useState('');
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchResults, setSearchResults] = useState<CustomerResult[]>([]);
  const [selectedCustomer, setSelectedCustomer] = useState<CustomerResult | null>(null);

  // ── بيانات الطلب ───────────────────────────────────────────
  const [creditsQty, setCreditsQty] = useState('');
  const [exactAmount, setExactAmount] = useState('');
  const [senderPhone, setSenderPhone] = useState('');
  const [senderName, setSenderName] = useState('');
  const [note, setNote] = useState('');

  // ── حالة الإرسال ───────────────────────────────────────────
  const [submitting, setSubmitting] = useState(false);
  const [createdOrder, setCreatedOrder] = useState<CreatedOrder | null>(null);

  // ── البحث عن عميل ──────────────────────────────────────────
  const handleSearch = useCallback(async () => {
    const q = searchQuery.trim();
    if (!q) return;
    setSearchLoading(true);
    setSearchResults([]);
    setSelectedCustomer(null);
    setCreatedOrder(null);

    const { data, error } = await supabase
      .from('profiles')
      .select('id, email, phone, full_name, wallet_balance, role')
      .eq('role', 'user')
      .or(`email.ilike.%${q}%,phone.ilike.%${q}%,full_name.ilike.%${q}%`)
      .limit(8);

    if (error) {
      toast.error('خطأ في البحث: ' + error.message);
    } else {
      setSearchResults((data ?? []) as CustomerResult[]);
      if ((data?.length ?? 0) === 0) {
        toast.info('لم يُعثر على عميل بهذه البيانات');
      }
    }
    setSearchLoading(false);
  }, [searchQuery]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch();
  };

  // ── إنشاء الطلب ────────────────────────────────────────────
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCustomer) { toast.error('يرجى اختيار عميل أولاً'); return; }

    const qty = parseInt(creditsQty, 10);
    const amt = parseFloat(exactAmount);

    if (!Number.isInteger(qty) || qty < 1) { toast.error('عدد الكريدت غير صحيح'); return; }
    if (!isFinite(amt) || amt <= 0) { toast.error('المبلغ غير صحيح'); return; }
    if (!senderPhone.trim()) { toast.error('رقم المحوّل مطلوب'); return; }

    setSubmitting(true);
    setCreatedOrder(null);

    const { data: { session } } = await supabase.auth.getSession();
    if (!session) { toast.error('انتهت جلستك. يرجى تسجيل الدخول مجدداً'); setSubmitting(false); return; }

    const { data, error } = await supabase.functions.invoke('admin-test-order', {
      body: {
        customer_id: selectedCustomer.id,
        credits_qty: qty,
        exact_amount: amt,
        sender_phone: senderPhone.trim(),
        sender_name: senderName.trim() || undefined,
        note: note.trim() || undefined,
      },
    });

    setSubmitting(false);

    if (error || !data?.ok) {
      const errMsg = error?.context
        ? await (error.context as any).text?.().catch(() => error.message)
        : error?.message;
      toast.error('فشل إنشاء الطلب: ' + (data?.error ?? errMsg ?? 'خطأ غير معروف'));
      return;
    }

    setCreatedOrder(data as CreatedOrder);
    toast.success(`✅ تم إنشاء طلب الاختبار رقم #${data.order_number}`);
  };

  const copyToClipboard = (text: string, label: string) => {
    navigator.clipboard.writeText(text).then(() => toast.success(`تم نسخ ${label}`));
  };

  const reset = () => {
    setSelectedCustomer(null);
    setSearchResults([]);
    setSearchQuery('');
    setCreditsQty('');
    setExactAmount('');
    setSenderPhone('');
    setSenderName('');
    setNote('');
    setCreatedOrder(null);
  };

  return (
    <AdminLayout>
      <div className="p-4 md:p-6 max-w-3xl mx-auto space-y-6" dir="rtl">

        {/* ── Header ── */}
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
            <FlaskConical className="w-5 h-5 text-primary" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-foreground">طلب شحن تجريبي</h1>
            <p className="text-sm text-muted-foreground">
              إنشاء طلب شحن حقيقي باسم عميل بمبلغ يدوي محدد — للاختبار فقط
            </p>
          </div>
        </div>

        {/* ── نبذة توضيحية ── */}
        <Alert>
          <Info className="h-4 w-4" />
          <AlertTitle>كيف يعمل هذا القسم؟</AlertTitle>
          <AlertDescription className="text-sm leading-relaxed">
            يُنشئ الطلب <strong>حقيقياً</strong> في قاعدة البيانات وتُرسَل مهمة الفحص للجهاز المتصل.
            يمكنك تحديد <strong>المبلغ الكامل يدوياً</strong> (بدون قرش قائد) — مفيد لاختبار
            هل يكتشف التطبيق رسائل تحويل موجودة بالفعل على الجهاز.
          </AlertDescription>
        </Alert>

        {/* ── خطوة 1: اختيار العميل ── */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <User className="w-4 h-4 text-primary" />
              الخطوة 1 — اختر العميل
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* Search bar */}
            <div className="flex gap-2">
              <Input
                placeholder="ابحث بالإيميل أو رقم الهاتف أو الاسم..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                className="flex-1"
                dir="ltr"
              />
              <Button
                type="button"
                variant="secondary"
                onClick={handleSearch}
                disabled={searchLoading || !searchQuery.trim()}
              >
                {searchLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              </Button>
            </div>

            {/* Search results */}
            {searchResults.length > 0 && !selectedCustomer && (
              <div className="border rounded-md divide-y max-h-64 overflow-y-auto">
                {searchResults.map(c => (
                  <button
                    key={c.id}
                    type="button"
                    onClick={() => { setSelectedCustomer(c); setSearchResults([]); }}
                    className="w-full text-right px-4 py-3 hover:bg-muted/60 transition-colors flex items-center justify-between gap-3"
                  >
                    <div className="min-w-0">
                      <p className="font-medium text-sm truncate">{c.full_name ?? 'بدون اسم'}</p>
                      <p className="text-xs text-muted-foreground truncate" dir="ltr">{c.email ?? c.phone ?? c.id}</p>
                    </div>
                    <Badge variant="outline" className="shrink-0 text-xs">
                      {c.wallet_balance.toLocaleString()} Credit
                    </Badge>
                  </button>
                ))}
              </div>
            )}

            {/* Selected customer */}
            {selectedCustomer && (
              <div className="flex items-center justify-between p-3 rounded-lg bg-primary/5 border border-primary/20">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="w-8 h-8 rounded-full bg-primary/20 flex items-center justify-center shrink-0">
                    <User className="w-4 h-4 text-primary" />
                  </div>
                  <div className="min-w-0">
                    <p className="font-semibold text-sm truncate">{selectedCustomer.full_name ?? 'بدون اسم'}</p>
                    <p className="text-xs text-muted-foreground truncate" dir="ltr">
                      {selectedCustomer.email ?? selectedCustomer.phone}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <Badge variant="secondary">{selectedCustomer.wallet_balance.toLocaleString()} Credit</Badge>
                  <Button variant="ghost" size="sm" onClick={() => setSelectedCustomer(null)}>
                    <XCircle className="w-4 h-4" />
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* ── خطوة 2: تفاصيل الطلب ── */}
        <Card className={!selectedCustomer ? 'opacity-50 pointer-events-none' : ''}>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <CreditCard className="w-4 h-4 text-primary" />
              الخطوة 2 — تفاصيل الطلب
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-5">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {/* عدد الكريدت */}
                <div className="space-y-1.5">
                  <Label htmlFor="credits_qty" className="flex items-center gap-1.5 text-sm font-medium">
                    <Hash className="w-3.5 h-3.5" />
                    عدد الكريدت
                  </Label>
                  <Input
                    id="credits_qty"
                    type="number"
                    min="1"
                    step="1"
                    placeholder="مثال: 100"
                    value={creditsQty}
                    onChange={e => setCreditsQty(e.target.value)}
                    required
                    dir="ltr"
                  />
                </div>

                {/* المبلغ المطلوب */}
                <div className="space-y-1.5">
                  <Label htmlFor="exact_amount" className="flex items-center gap-1.5 text-sm font-medium">
                    <DollarSign className="w-3.5 h-3.5" />
                    المبلغ الكامل (جنيه)
                  </Label>
                  <Input
                    id="exact_amount"
                    type="number"
                    min="0.01"
                    step="0.01"
                    placeholder="مثال: 600.50"
                    value={exactAmount}
                    onChange={e => setExactAmount(e.target.value)}
                    required
                    dir="ltr"
                  />
                  <p className="text-xs text-muted-foreground">
                    أنت تتحكم في المبلغ بالكامل — لا يوجد قرش قائد تلقائي
                  </p>
                </div>
              </div>

              {/* رقم المحوّل */}
              <div className="space-y-1.5">
                <Label htmlFor="sender_phone" className="flex items-center gap-1.5 text-sm font-medium">
                  <Phone className="w-3.5 h-3.5" />
                  رقم المحوّل (رقم الهاتف الذي سيرسل منه)
                </Label>
                <Input
                  id="sender_phone"
                  type="tel"
                  placeholder="مثال: 01222692182"
                  value={senderPhone}
                  onChange={e => setSenderPhone(e.target.value)}
                  required
                  dir="ltr"
                />
                <p className="text-xs text-muted-foreground">
                  التطبيق سيبحث في رسائل SMS عن تحويل من هذا الرقم بالمبلغ المحدد
                </p>
              </div>

              {/* اسم المُرسِل */}
              <div className="space-y-1.5">
                <Label htmlFor="sender_name" className="text-sm font-medium">
                  اسم المُرسِل (اختياري)
                </Label>
                <Input
                  id="sender_name"
                  type="text"
                  placeholder="مثال: نادر"
                  value={senderName}
                  onChange={e => setSenderName(e.target.value)}
                  dir="rtl"
                />
              </div>

              {/* ملاحظة */}
              <div className="space-y-1.5">
                <Label htmlFor="note" className="text-sm font-medium">
                  ملاحظة (اختياري)
                </Label>
                <Textarea
                  id="note"
                  placeholder="سبب الطلب أو ما تختبره..."
                  value={note}
                  onChange={e => setNote(e.target.value)}
                  rows={2}
                  className="resize-none"
                />
              </div>

              {/* Summary preview */}
              {creditsQty && exactAmount && senderPhone && selectedCustomer && (
                <div className="p-3 rounded-lg bg-muted/60 border text-sm space-y-1.5">
                  <p className="font-medium text-foreground">ملخص الطلب:</p>
                  <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-muted-foreground">
                    <span>العميل:</span>
                    <span className="font-medium text-foreground truncate">{selectedCustomer.full_name ?? selectedCustomer.email}</span>
                    <span>الكريدت:</span>
                    <span className="font-medium text-foreground" dir="ltr">{creditsQty} Credit</span>
                    <span>المبلغ المطلوب:</span>
                    <span className="font-bold text-primary" dir="ltr">{parseFloat(exactAmount || '0').toFixed(2)} جنيه</span>
                    <span>رقم المحوّل:</span>
                    <span className="font-medium text-foreground" dir="ltr">{senderPhone}</span>
                  </div>
                </div>
              )}

              <Button
                type="submit"
                className="w-full"
                disabled={submitting || !selectedCustomer || !creditsQty || !exactAmount || !senderPhone.trim()}
              >
                {submitting
                  ? <><Loader2 className="w-4 h-4 animate-spin ml-2" />جاري الإنشاء...</>
                  : <><FlaskConical className="w-4 h-4 ml-2" />إنشاء طلب الاختبار</>
                }
              </Button>
            </form>
          </CardContent>
        </Card>

        {/* ── النتيجة ── */}
        {createdOrder && (
          <Card className="border-green-500/40 bg-green-500/5">
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center gap-2 text-base text-green-600 dark:text-green-400">
                <CheckCircle2 className="w-5 h-5" />
                تم إنشاء الطلب وإرساله للجهاز ✅
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 gap-3 text-sm">

                {/* رقم الطلب */}
                <div className="col-span-2 flex items-center justify-between p-3 rounded-md bg-background border">
                  <span className="text-muted-foreground">رقم الطلب</span>
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-lg text-foreground">#{createdOrder.order_number}</span>
                    <Button variant="ghost" size="sm" className="h-7 w-7 p-0"
                      onClick={() => copyToClipboard(String(createdOrder.order_number), 'رقم الطلب')}>
                      <Copy className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </div>

                {/* العميل */}
                <div className="col-span-2 p-3 rounded-md bg-background border">
                  <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                    <User className="w-3 h-3" /> العميل
                  </p>
                  <p className="font-semibold text-foreground">{createdOrder.customer_name ?? '—'}</p>
                  <p className="text-xs text-muted-foreground" dir="ltr">{createdOrder.customer_email}</p>
                </div>

                {/* المبلغ */}
                <div className="p-3 rounded-md bg-background border">
                  <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                    <DollarSign className="w-3 h-3" /> المبلغ المطلوب
                  </p>
                  <p className="font-bold text-primary text-lg" dir="ltr">
                    {Number(createdOrder.expected_amount).toFixed(2)} جنيه
                  </p>
                </div>

                {/* الكريدت */}
                <div className="p-3 rounded-md bg-background border">
                  <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                    <Hash className="w-3 h-3" /> الكريدت
                  </p>
                  <p className="font-bold text-foreground text-lg" dir="ltr">
                    {createdOrder.credits_qty} Credit
                  </p>
                </div>

                {/* رقم المحوّل */}
                <div className="col-span-2 flex items-center justify-between p-3 rounded-md bg-background border">
                  <div>
                    <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                      <Phone className="w-3 h-3" /> رقم المحوّل المتوقع
                    </p>
                    <p className="font-semibold text-foreground" dir="ltr">{createdOrder.sender_phone}</p>
                    {createdOrder.sender_name && (
                      <p className="text-xs text-muted-foreground">{createdOrder.sender_name}</p>
                    )}
                  </div>
                  <Button variant="ghost" size="sm" className="h-7 w-7 p-0 shrink-0"
                    onClick={() => copyToClipboard(createdOrder.sender_phone, 'رقم المحوّل')}>
                    <Copy className="w-3.5 h-3.5" />
                  </Button>
                </div>

                {/* انتهاء الطلب */}
                <div className="col-span-2 p-3 rounded-md bg-background border">
                  <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                    <Clock className="w-3 h-3" /> ينتهي في
                  </p>
                  <p className="font-medium text-foreground text-sm" dir="ltr">
                    {new Date(createdOrder.expires_at).toLocaleString('ar-EG')}
                  </p>
                </div>
              </div>

              <Separator />

              <div className="space-y-1 text-xs text-muted-foreground">
                <p>• الطلب أُرسل للجهاز المتصل وبدأ الفحص تلقائياً.</p>
                <p>• التطبيق سيبحث عن رسالة تحويل من <strong dir="ltr">{createdOrder.sender_phone}</strong> بمبلغ <strong>{Number(createdOrder.expected_amount).toFixed(2)} جنيه</strong>.</p>
                <p>• يمكنك تتبع الطلب من صفحة <strong>طلبات الشحن</strong>.</p>
              </div>

              <Button variant="outline" className="w-full" onClick={reset}>
                إنشاء طلب اختبار جديد
              </Button>
            </CardContent>
          </Card>
        )}
      </div>
    </AdminLayout>
  );
}
