/**
 * PaymentOrderPage — صفحة إكمال الدفع (الخطوة الثانية)
 * بيانات الطلب مقفولة من Server فقط — لا يمكن تعديل أي قيمة مالية.
 * المستخدم يُدخل فقط: رقم محفظته + اسم صاحب المحفظة.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowRight, Loader2, AlertCircle, CheckCircle2, Clock, Copy,
  Check, Phone, Shield, Banknote, Lock, AlertTriangle, XCircle, Wallet,
  RefreshCcw, CreditCard, Trash2, ChevronDown, ChevronUp,
} from 'lucide-react';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel,
  AlertDialogContent, AlertDialogDescription, AlertDialogFooter,
  AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import { toast } from 'sonner';

/* ── أنواع ──────────────────────────────────────────────────── */
interface PaymentOrder {
  id: string;
  order_number: number;
  credits_qty: number;
  base_amount: number;
  discount_amount: number;
  expected_amount: number;
  fingerprint: number;
  status: string;
  expires_at: string;
  offer_id: string | null;
  sender_phone: string | null;
  sender_name: string | null;
}

/* ── نسخ نص ─────────────────────────────────────────────────── */
function CopyButton({ value, label }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };
  return (
    <button
      type="button"
      onClick={copy}
      className="inline-flex items-center gap-1.5 text-xs px-2.5 py-1.5 rounded-md bg-primary/10 hover:bg-primary/20 text-primary transition-colors shrink-0"
    >
      {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
      {label ?? (copied ? 'تم النسخ' : 'نسخ')}
    </button>
  );
}

/* ── عداد تنازلي ─────────────────────────────────────────────── */
function Countdown({ expiresAt }: { expiresAt: string }) {
  const calc = () => Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
  const [secs, setSecs] = useState(calc);

  useEffect(() => {
    const id = setInterval(() => setSecs(calc), 1000);
    return () => clearInterval(id);
  });

  const mins = Math.floor(secs / 60);
  const s    = secs % 60;
  const critical = secs < 120; // أقل من دقيقتين

  if (secs === 0) return (
    <span className="flex items-center gap-1.5 text-sm font-bold text-destructive">
      <XCircle className="w-4 h-4" />
      انتهت الصلاحية
    </span>
  );

  return (
    <span className={`flex items-center gap-1.5 text-sm font-bold tabular-nums ${critical ? 'text-destructive animate-pulse' : 'text-amber-600'}`}>
      <Clock className="w-4 h-4 shrink-0" />
      {String(mins).padStart(2, '0')}:{String(s).padStart(2, '0')}
    </span>
  );
}

/* ── شارة الحالة ─────────────────────────────────────────────── */
const STATUS_UI: Record<string, { label: string; color: string; icon: React.ElementType }> = {
  awaiting_payment: { label: 'انتظار الدفع',   color: 'bg-amber-500/10 text-amber-600',     icon: Clock },
  scanning:         { label: 'جاري الفحص',      color: 'bg-blue-500/10 text-blue-600',       icon: Loader2 },
  confirmed:        { label: 'تم التأكيد ✓',    color: 'bg-green-500/10 text-green-600',     icon: CheckCircle2 },
  expired:          { label: 'منتهي الصلاحية', color: 'bg-muted/40 text-muted-foreground', icon: XCircle },
  cancelled:        { label: 'ملغي',            color: 'bg-muted/40 text-muted-foreground', icon: XCircle },
  failed:           { label: 'فشل الفحص',       color: 'bg-destructive/10 text-destructive', icon: AlertTriangle },
  duplicate:        { label: 'عملية مكررة',     color: 'bg-purple-500/10 text-purple-600',  icon: Shield },
  amount_mismatch:  { label: 'مبلغ غير مطابق', color: 'bg-orange-500/10 text-orange-600',  icon: AlertTriangle },
  not_found:        { label: 'لم يتم العثور',  color: 'bg-muted/40 text-muted-foreground', icon: AlertCircle },
};

/* ── الصفحة الرئيسية ─────────────────────────────────────────── */
export default function PaymentOrderPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate    = useNavigate();
  const { profile } = useAuth();

  const [order,        setOrder]        = useState<PaymentOrder | null>(null);
  const [vfNumber,     setVfNumber]     = useState('01097273680');
  const [loading,      setLoading]      = useState(true);
  const [submitting,   setSubmitting]   = useState(false);
  const [cancelling,   setCancelling]   = useState(false);
  const [senderPhone,  setSenderPhone]  = useState('');
  const [senderName,   setSenderName]   = useState('');
  const [confirmed,    setConfirmed]    = useState(false);
  const [showDetails,  setShowDetails]  = useState(false);
  const submittedRef = useRef(false);
  const channelRef   = useRef<ReturnType<typeof supabase.channel> | null>(null);

  /* ── جلب بيانات الطلب ─────────────────────────────────────── */
  const fetchOrder = useCallback(async () => {
    if (!orderId || !profile?.id) return;
    const { data, error } = await supabase
      .from('payment_orders')
      .select('*')
      .eq('id', orderId)
      .eq('user_id', profile.id)
      .maybeSingle();
    if (error || !data) {
      toast.error('الطلب غير موجود أو غير مصرح');
      navigate('/store/wallet/topup');
      return;
    }
    setOrder(data as PaymentOrder);
    setLoading(false);
  }, [orderId, profile?.id, navigate]);

  useEffect(() => { fetchOrder(); }, [fetchOrder]);

  /* ── رقم فودافون ──────────────────────────────────────────── */
  useEffect(() => {
    supabase.from('system_settings').select('value').eq('key', 'vodafone_cash_number').maybeSingle()
      .then(({ data }) => { if (data?.value) setVfNumber(data.value); });
  }, []);

  /* ── Realtime: تحديث حالة الطلب لحظياً ──────────────────── */
  useEffect(() => {
    if (!orderId || !profile?.id) return;
    const ch = supabase
      .channel(`payment-order-${orderId}`)
      .on('postgres_changes', {
        event: 'UPDATE', schema: 'public', table: 'payment_orders',
        filter: `id=eq.${orderId}`,
      }, (payload) => {
        const updated = payload.new as PaymentOrder;
        setOrder(prev => prev ? { ...prev, ...updated } : null);
        if (updated.status === 'confirmed') {
          toast.success('✅ تم تأكيد طلب الشحن وإضافة الكريدات!');
        } else if (updated.status === 'amount_mismatch') {
          toast.warning('⚠️ المبلغ المحوَّل غير مطابق للمطلوب.');
        } else if (updated.status === 'duplicate') {
          toast.error('رقم العملية مستخدم من قبل — تم رفض الطلب.');
        } else if (updated.status === 'expired') {
          toast.error('انتهت صلاحية الطلب.');
        }
      })
      .subscribe();
    channelRef.current = ch;
    return () => { supabase.removeChannel(ch).catch(() => {}); };
  }, [orderId, profile?.id]);

  /* ── إرسال بيانات الدفع ──────────────────────────────────── */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submittedRef.current) { toast.error('جارٍ معالجة طلبك…'); return; }
    if (!order || !orderId) return;
    if (order.status !== 'awaiting_payment') {
      toast.error('الطلب ليس في حالة انتظار الدفع');
      return;
    }
    if (new Date(order.expires_at).getTime() <= Date.now()) {
      toast.error('انتهت صلاحية الطلب. يرجى إنشاء طلب جديد.');
      return;
    }

    submittedRef.current = true;
    setSubmitting(true);

    try {
      const { data, error } = await supabase.functions.invoke('submit-payment-details', {
        body: {
          order_id:     orderId,
          sender_phone: senderPhone.trim(),
          sender_name:  senderName.trim() || null,
        },
      });

      if (error) {
        let msg = 'تعذر إرسال بيانات الدفع.';
        try {
          const t = await (error as any)?.context?.text?.();
          if (t) { const j = JSON.parse(t); msg = j.error ?? j.message ?? msg; }
        } catch { /**/ }
        toast.error(msg);
        submittedRef.current = false;
        return;
      }

      if (!data?.ok) {
        toast.error(data?.message ?? data?.reason ?? 'تعذر إرسال البيانات');
        submittedRef.current = false;
        return;
      }

      toast.success('تم إرسال بياناتك. جارٍ فحص التحويل تلقائياً…');
      setOrder(prev => prev ? { ...prev, status: 'scanning', sender_phone: senderPhone, sender_name: senderName } : null);
    } catch {
      toast.error('تعذر الاتصال بالخادم. حاول مرة أخرى.');
      submittedRef.current = false;
    } finally {
      setSubmitting(false);
    }
  };

  /* ── إلغاء الطلب ────────────────────────────────────────── */
  const handleCancel = async () => {
    if (!order || !orderId) return;
    setCancelling(true);
    try {
      const { data, error } = await supabase.functions.invoke('cancel-payment-order', {
        body: { order_id: orderId },
      });
      if (error || !data?.ok) {
        let msg = 'تعذر إلغاء الطلب.';
        try {
          const t = await (error as any)?.context?.text?.();
          if (t) { const j = JSON.parse(t); msg = j.error ?? j.message ?? msg; }
        } catch { /**/ }
        toast.error(msg);
        return;
      }
      toast.success('تم إلغاء الطلب بنجاح.');
      setOrder(prev => prev ? { ...prev, status: 'cancelled' } : null);
    } catch {
      toast.error('تعذر الاتصال بالخادم. حاول مرة أخرى.');
    } finally {
      setCancelling(false);
    }
  };

  /* ── حالات التحميل والأخطاء ──────────────────────────────── */
  if (loading) return (
    <CustomerLayout>
      <div className="flex justify-center py-20">
        <Loader2 className="w-6 h-6 animate-spin text-muted-foreground" />
      </div>
    </CustomerLayout>
  );

  if (!order) return null;

  const statusUi   = STATUS_UI[order.status] ?? STATUS_UI['awaiting_payment'];
  const StatusIcon = statusUi.icon;
  const amtStr     = order.expected_amount.toFixed(2);
  const isExpired  = new Date(order.expires_at).getTime() <= Date.now() || order.status === 'expired';
  const isTerminal = ['confirmed', 'expired', 'cancelled', 'failed', 'duplicate'].includes(order.status);
  const isAwaiting = order.status === 'awaiting_payment';
  const isScanning = order.status === 'scanning';
  const isCancellable = ['awaiting_payment'].includes(order.status) && !isExpired;

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-lg mx-auto space-y-5">

        {/* رجوع + إلغاء الطلب */}
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <button
            onClick={() => navigate('/store/wallet/topup')}
            className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            <ArrowRight className="w-4 h-4" />
            العودة لطلبات الشحن
          </button>

          {/* زر إلغاء الطلب — يظهر فقط إذا كان الطلب قابلاً للإلغاء */}
          {isCancellable && (
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <button
                  className="flex items-center gap-1.5 text-xs text-destructive hover:text-destructive/80 transition-colors px-3 py-1.5 rounded-lg border border-destructive/30 hover:bg-destructive/5"
                  disabled={cancelling}
                >
                  {cancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                  إلغاء الطلب
                </button>
              </AlertDialogTrigger>
              <AlertDialogContent className="max-w-[calc(100%-2rem)] md:max-w-lg">
                <AlertDialogHeader>
                  <AlertDialogTitle>تأكيد إلغاء الطلب</AlertDialogTitle>
                  <AlertDialogDescription>
                    هل أنت متأكد من إلغاء طلب رقم <strong>#{order.order_number}</strong>؟
                    لن تستطيع إنشاء طلب جديد إلا بعد الإلغاء.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>تراجع</AlertDialogCancel>
                  <AlertDialogAction
                    className="bg-destructive hover:bg-destructive/90 text-destructive-foreground"
                    onClick={handleCancel}
                  >
                    نعم، إلغاء الطلب
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}
        </div>

        {/* العنوان */}
        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <CreditCard className="w-5 h-5 text-primary" />
            إكمال الدفع
          </h1>
          <p className="text-sm text-muted-foreground">
            طلب رقم <span className="font-mono font-semibold text-foreground">#{order.order_number}</span>
          </p>
        </div>

        {/* بيانات الطلب المقفولة */}
        <Card className="border-border shadow-sm overflow-hidden">
          <div className="h-1.5 bg-primary" />
          <CardHeader className="pb-2">
            <div className="flex items-center justify-between flex-wrap gap-2">
              <div className="flex items-center gap-2">
                <Lock className="w-4 h-4 text-muted-foreground" />
                <CardTitle className="text-sm text-muted-foreground font-medium">بيانات مقفولة من الخادم</CardTitle>
              </div>
              <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium ${statusUi.color}`}>
                <StatusIcon className={`w-3.5 h-3.5 shrink-0 ${isScanning ? 'animate-spin' : ''}`} />
                {statusUi.label}
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="rounded-xl border border-border bg-muted/20 p-3 space-y-0">
              {/* عدد الكريدات */}
              <div className="flex items-center justify-between py-2 border-b border-border/40 gap-2">
                <span className="text-sm text-muted-foreground flex items-center gap-1.5">
                  <Wallet className="w-3.5 h-3.5" />
                  الكريدات
                </span>
                <span className="text-sm font-bold text-primary">{order.credits_qty} Credit</span>
              </div>
              {/* المبلغ الأساسي */}
              {order.discount_amount > 0 && (
                <div className="flex items-center justify-between py-2 border-b border-border/40 gap-2">
                  <span className="text-sm text-muted-foreground flex items-center gap-1.5">
                    <Banknote className="w-3.5 h-3.5" />
                    المبلغ الأساسي
                  </span>
                  <span className="text-sm font-medium">{order.base_amount.toFixed(2)} جنيه</span>
                </div>
              )}
              {/* الخصم */}
              {order.discount_amount > 0 && (
                <div className="flex items-center justify-between py-2 border-b border-border/40 gap-2">
                  <span className="text-xs text-green-600 flex items-center gap-1.5">
                    <Badge className="text-[10px] bg-green-500/15 text-green-600 border-green-200 hover:bg-green-500/15">خصم</Badge>
                  </span>
                  <span className="text-xs font-medium text-green-600">- {order.discount_amount.toFixed(2)} جنيه</span>
                </div>
              )}
              {/* قيمة التعرف التلقائي — مخفية عن المستخدم، Server يولدها */}
              {/* المبلغ الإجمالي */}
              <div className="pt-2 flex items-center justify-between gap-2">
                <span className="text-sm font-semibold text-foreground">المبلغ الإجمالي للتحويل</span>
                <div className="flex items-center gap-2">
                  <span className="text-xl font-bold text-primary" dir="ltr">{amtStr} جنيه</span>
                  <CopyButton value={amtStr} />
                </div>
              </div>
            </div>

            {/* رقم فودافون */}
            <div className="flex items-center justify-between gap-3 px-3 py-3 rounded-xl border border-primary/20 bg-primary/5">
              <div>
                <p className="text-xs text-muted-foreground mb-0.5 flex items-center gap-1">
                  <Phone className="w-3 h-3" />
                  رقم فودافون كاش الرسمي
                </p>
                <p className="text-lg font-bold tracking-widest text-primary" dir="ltr">{vfNumber}</p>
              </div>
              <CopyButton value={vfNumber} label="نسخ الرقم" />
            </div>

            {/* عداد تنازلي */}
            {!isTerminal && (
              <div className="flex items-center justify-between px-1">
                <span className="text-xs text-muted-foreground">صلاحية الطلب</span>
                <Countdown expiresAt={order.expires_at} />
              </div>
            )}

            {/* تنبيه المبلغ بالقروش */}
            {isAwaiting && (
              <div className="p-3 rounded-xl border border-amber-300 bg-amber-50 space-y-1">
                <p className="text-xs font-bold text-amber-800 flex items-center gap-1.5">
                  <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                  مهم جداً
                </p>
                <p className="text-xs text-amber-700 leading-relaxed">
                  يجب تحويل المبلغ بالقروش بالضبط: <strong dir="ltr">{amtStr} جنيه</strong>.
                  أي مبلغ مختلف سيُرفض تلقائياً.
                </p>
              </div>
            )}
          </CardContent>
        </Card>

        {/* نموذج بيانات المُحوِّل — يظهر فقط في حالة انتظار الدفع */}
        {isAwaiting && !isExpired && (
          <Card className="border-border shadow-sm">
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <Phone className="w-4 h-4 text-primary" />
                بيانات المُحوِّل
              </CardTitle>
              <p className="text-xs text-muted-foreground">أدخل بيانات محفظة فودافون كاش التي ستحوّل منها</p>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div className="space-y-2">
                  <Label className="text-xs text-muted-foreground">رقم فودافون كاش المُحوِّل (رقمك) *</Label>
                  <Input
                    type="tel"
                    value={senderPhone}
                    onChange={e => setSenderPhone(e.target.value)}
                    placeholder="01012345678"
                    dir="ltr"
                    required
                    className="text-left"
                  />
                </div>
                <div className="space-y-2">
                  <Label className="text-xs text-muted-foreground">اسم صاحب المحفظة (موصى به)</Label>
                  <Input
                    value={senderName}
                    onChange={e => setSenderName(e.target.value)}
                    placeholder="الاسم الأول على الأقل"
                  />
                </div>

                {/* تأكيد */}
                <label className="flex items-start gap-2.5 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={e => setConfirmed(e.target.checked)}
                    className="mt-0.5 w-4 h-4 rounded border-border accent-primary"
                  />
                  <span className="text-xs text-muted-foreground leading-relaxed">
                    حوّلت المبلغ <strong dir="ltr">{amtStr} جنيه</strong> بالضبط إلى الرقم{' '}
                    <strong dir="ltr">{vfNumber}</strong> وأدخلت بياناتي الصحيحة.
                  </span>
                </label>

                <Button
                  type="submit"
                  className="w-full h-12 text-base font-semibold gap-2"
                  disabled={submitting || !senderPhone.trim() || !confirmed}
                >
                  {submitting
                    ? <><Loader2 className="w-4 h-4 animate-spin" />جارٍ الإرسال…</>
                    : <><CheckCircle2 className="w-4 h-4" />تأكيد التحويل</>
                  }
                </Button>
              </form>
            </CardContent>
          </Card>
        )}

        {/* حالة الفحص الجارية */}
        {isScanning && (
          <Card className="border-blue-200 bg-blue-50/50 shadow-sm">
            <CardContent className="pt-5 pb-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <Loader2 className="w-8 h-8 text-blue-500 animate-spin" />
                <p className="text-sm font-semibold text-blue-700">جارٍ فحص التحويل تلقائياً</p>
                <p className="text-xs text-blue-600 leading-relaxed">
                  النظام يبحث عن رسالة Vodafone Cash المطابقة. قد يستغرق الأمر بضع دقائق.
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* تأكيد ناجح */}
        {order.status === 'confirmed' && (
          <Card className="border-green-200 bg-green-50/50 shadow-sm">
            <CardContent className="pt-5 pb-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <CheckCircle2 className="w-10 h-10 text-green-500" />
                <p className="text-base font-bold text-green-700">تم تأكيد الدفع بنجاح! 🎉</p>
                <p className="text-sm text-green-600">
                  تم إضافة <strong>{order.credits_qty} Credit</strong> إلى محفظتك.
                </p>
                <Button className="mt-2 gap-2" onClick={() => navigate('/store/wallet')}>
                  <Wallet className="w-4 h-4" />
                  عرض المحفظة
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* طلب منتهي أو ملغي */}
        {(isExpired || order.status === 'cancelled' || order.status === 'expired') && (
          <Card className="border-border bg-muted/30 shadow-sm">
            <CardContent className="pt-5 pb-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <XCircle className="w-8 h-8 text-muted-foreground" />
                <p className="text-sm font-semibold text-foreground">
                  {order.status === 'cancelled' ? 'تم إلغاء الطلب' : 'انتهت صلاحية الطلب'}
                </p>
                <p className="text-xs text-muted-foreground">يمكنك إنشاء طلب شحن جديد.</p>
                <Button
                  variant="outline"
                  className="mt-1 gap-2"
                  onClick={() => navigate('/store/wallet/topup')}
                >
                  <RefreshCcw className="w-4 h-4" />
                  إنشاء طلب جديد
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* فشل أو مبلغ غير مطابق */}
        {(order.status === 'failed' || order.status === 'amount_mismatch' || order.status === 'not_found') && (
          <Card className="border-destructive/20 bg-destructive/5 shadow-sm">
            <CardContent className="pt-5 pb-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <AlertTriangle className="w-8 h-8 text-destructive" />
                <p className="text-sm font-semibold text-destructive">
                  {order.status === 'amount_mismatch'
                    ? 'المبلغ غير مطابق'
                    : order.status === 'not_found'
                    ? 'لم يتم العثور على التحويل'
                    : 'فشل التحقق'}
                </p>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  {order.status === 'amount_mismatch'
                    ? `المبلغ المطلوب ${amtStr} جنيه. تواصل مع الدعم إذا كنت قد حوّلت بالفعل.`
                    : 'تواصل مع الدعم أو انتظر إعادة الفحص.'}
                </p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* عملية مكررة */}
        {order.status === 'duplicate' && (
          <Card className="border-purple-200 bg-purple-50/50 shadow-sm">
            <CardContent className="pt-5 pb-5">
              <div className="flex flex-col items-center gap-3 text-center">
                <Shield className="w-8 h-8 text-purple-500" />
                <p className="text-sm font-semibold text-purple-700">تم رفض الطلب: عملية مكررة</p>
                <p className="text-xs text-muted-foreground">رقم العملية مستخدم من قبل. تواصل مع الدعم.</p>
              </div>
            </CardContent>
          </Card>
        )}

        {/* ───── تفاصيل الطلب الكاملة ───── */}
        <Card className="border-border shadow-sm">
          <button
            type="button"
            className="w-full flex items-center justify-between px-4 py-3 text-sm font-semibold text-foreground hover:bg-muted/30 transition-colors rounded-xl"
            onClick={() => setShowDetails(v => !v)}
          >
            <span className="flex items-center gap-2">
              <AlertCircle className="w-4 h-4 text-muted-foreground" />
              تفاصيل الطلب
            </span>
            {showDetails
              ? <ChevronUp className="w-4 h-4 text-muted-foreground" />
              : <ChevronDown className="w-4 h-4 text-muted-foreground" />}
          </button>
          {showDetails && (
            <CardContent className="pt-0 pb-4 space-y-2">
              <div className="divide-y divide-border/60 rounded-lg border border-border overflow-hidden text-xs">
                {[
                  { label: 'رقم الطلب',     value: `#${order.order_number}` },
                  { label: 'الحالة',         value: statusUi.label },
                  { label: 'عدد الكريدات',  value: `${order.credits_qty} Credit` },
                  { label: 'المبلغ الإجمالي', value: `${amtStr} جنيه` },
                  { label: 'ينتهي في',      value: new Date(order.expires_at).toLocaleString('ar-EG') },
                  ...(order.sender_phone ? [{ label: 'رقم المُحوِّل', value: order.sender_phone }] : []),
                  ...(order.sender_name  ? [{ label: 'اسم المُحوِّل', value: order.sender_name  }] : []),
                ].map(({ label, value }) => (
                  <div key={label} className="flex items-center justify-between px-3 py-2 gap-2">
                    <span className="text-muted-foreground shrink-0">{label}</span>
                    <span className="font-medium text-foreground text-left" dir="ltr">{value}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          )}
        </Card>
      </div>
    </CustomerLayout>
  );
}
