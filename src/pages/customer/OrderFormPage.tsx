import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowRight, Loader2, AlertCircle, AlertTriangle,
  CheckCircle2, Wrench, Shield, Clock, Package, Banknote,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import type { ProviderService } from '@/types/types';
import { toast } from 'sonner';

/* ── Checkout summary row ─────────────────────────────────────────── */
function SummaryRow({ icon: Icon, label, value, bold }: {
  icon: React.ElementType; label: string; value: string; bold?: boolean
}) {
  return (
    <div className="flex items-center justify-between py-2.5 border-b border-border/50 last:border-0 gap-3">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Icon className="w-4 h-4 text-muted-foreground shrink-0" />
        {label}
      </div>
      <span className={`text-sm ${bold ? 'font-bold text-primary' : 'font-medium text-foreground'}`}>
        {value}
      </span>
    </div>
  );
}

export default function OrderFormPage() {
  const { serviceId } = useParams<{ serviceId: string }>();
  const navigate = useNavigate();
  const { profile } = useAuth();

  const [svc, setSvc] = useState<ProviderService | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [activeOrderId, setActiveOrderId] = useState<string | null>(null);
  const [failedOrderId, setFailedOrderId] = useState<string | null>(null);
  // Server-side idempotency key — regenerated per form load; user can reset to force new order
  const generateKey = () => `ik_${Date.now()}_${Math.random().toString(36).slice(2)}_${crypto.randomUUID?.() ?? Math.random().toString(36).slice(2)}`;
  const [idempotencyKey, setIdempotencyKey] = useState<string>(generateKey());
  const submittedRef = useRef(false);

  useEffect(() => {
    if (!serviceId) return;
    supabase.from('provider_services')
      .select('*').eq('id', serviceId).eq('store_enabled', true).maybeSingle()
      .then(({ data }) => { setSvc(data as ProviderService ?? null); setLoading(false); });
  }, [serviceId]);

  if (loading) return (
    <CustomerLayout>
      <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
    </CustomerLayout>
  );

  if (!svc) return (
    <CustomerLayout>
      <div className="flex flex-col items-center py-16 gap-3">
        <AlertCircle className="w-8 h-8 text-destructive" />
        <p className="text-sm text-muted-foreground">الخدمة غير موجودة أو غير متاحة</p>
        <Button variant="secondary" size="sm" onClick={() => navigate('/store/services')}>العودة للخدمات</Button>
      </div>
    </CustomerLayout>
  );

  const isAvailable = svc.status === 'active';
  const unitPrice = svc.customer_price ?? svc.final_credit_price ?? 0;
  const hasEnoughBalance = (profile?.wallet_balance ?? 0) >= unitPrice;

  const handleConfirm = async () => {
    if (submittedRef.current) { toast.error('جارٍ معالجة طلبك، انتظر لحظة…'); return; }
    if (!isAvailable) { toast.error('الخدمة غير متاحة حالياً'); return; }
    if (!hasEnoughBalance) { toast.error('رصيد غير كافٍ. تواصل مع الدعم لشحن المحفظة'); return; }

    submittedRef.current = true;
    setSubmitting(true);
    try {
      const { data, error } = await supabase.functions.invoke('create-order', {
        body: { service_id: serviceId, quantity: 1, idempotency_key: idempotencyKey },
      });

      if (error) {
        // Try to extract message from edge function response
        let msg = 'تعذر إنشاء الطلب. حاول مرة أخرى بعد قليل.';
        try { const t = await error?.context?.text?.(); if (t) { const j = JSON.parse(t); msg = j.safe_message ?? j.error ?? msg; } } catch { /**/ }
        toast.error(msg);
        submittedRef.current = false;
        return;
      }

      if (data?.active_order?.order_id) {
        // طلب مفتوح فعلي — لا يمكن إنشاء طلب جديد
        setActiveOrderId(data.active_order.order_id);
        toast.info(data?.safe_message || 'لديك طلب مفتوح. يمكنك متابعته.');
        return;
      }

      if (data?.success) {
        toast.success('تم إنشاء طلبك بنجاح 🎉');
        navigate(`/store/orders/${data.order_id}`);
        return;
      }

      // فشل إنشاء الطلب (مثلاً خطأ من المزود) — ليس طلباً مفتوحاً
      if (data?.order_id && data?.status === 'failed') {
        setFailedOrderId(data.order_id);
        toast.error(data?.safe_message ?? 'تعذر إنشاء الطلب. يمكنك المحاولة مرة أخرى.');
        submittedRef.current = false;
        return;
      }

      toast.error(data?.safe_message ?? 'تعذر إنشاء الطلب. حاول مرة أخرى.');
      submittedRef.current = false;
    } catch {
      toast.error('تعذر الاتصال بالخادم. حاول مرة أخرى بعد قليل.');
      submittedRef.current = false;
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-lg mx-auto space-y-5">
        {/* Back */}
        <button onClick={() => navigate('/store/services')}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ArrowRight className="w-4 h-4" />
          العودة للخدمات
        </button>

        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground">تأكيد الاشتراك</h1>
          <p className="text-sm text-muted-foreground">راجع تفاصيل طلبك قبل التأكيد</p>
        </div>

        {/* Checkout summary card */}
        <Card className="border-border shadow-sm overflow-hidden">
          <div className="h-1.5 bg-primary" />
          <CardHeader className="pb-2">
            <div className="flex items-center gap-2">
              <Badge className="bg-primary/10 text-primary border-primary/20 text-xs">اشتراك رسمي</Badge>
              {isAvailable
                ? <span className="flex items-center gap-1 text-xs text-green-600 font-medium"><CheckCircle2 className="w-3 h-3" />متاح</span>
                : <span className="flex items-center gap-1 text-xs text-amber-600"><Wrench className="w-3 h-3" />صيانة</span>
              }
            </div>
            <CardTitle className="text-base mt-1">
              {svc.display_name_ar ?? 'جيميناي برو 18 شهر'}
            </CardTitle>
            <p className="text-xs text-muted-foreground">{svc.display_name_en ?? 'Gemini AI Pro — 18 Months'}</p>
          </CardHeader>
          <CardContent className="space-y-3">
            {/* Summary rows — Latin digits, Credit unit */}
            <div className="rounded-xl border border-border bg-muted/20 p-3 space-y-0">
              <SummaryRow icon={Package}  label="الخدمة"          value={svc.display_name_ar ?? 'جيميناي برو 18 شهر'} />
              <SummaryRow icon={Clock}    label="المدة"            value="18 Months (540 Days)" />
              <SummaryRow icon={Banknote} label="السعر الإجمالي"   value={`${unitPrice.toFixed(1)} Credit`} bold />
            </div>

            {/* Description per spec */}
            <div className="text-xs text-muted-foreground bg-muted/30 rounded-lg p-3 leading-relaxed">
              📝 <span className="font-medium">رابط للحصول على عرض Gemini Pro لمدة 18 شهرًا.</span><br />
              أكمل عملية الشراء، ثم اضغط على الرابط لتفعيل عرض Gemini Pro لمدة 18 شهرًا.<br />
              <span className="text-green-700 font-medium">✓ لا تحتاج إلى بطاقة دفع، ويتم التفعيل عبر الرابط.</span>
            </div>

            {/* Balance check */}
            <div className="flex justify-between text-xs px-1">
              <span className="text-muted-foreground">رصيدك الحالي</span>
              <span className={hasEnoughBalance ? 'text-green-600 font-semibold' : 'text-destructive font-semibold'}>
                {(profile?.wallet_balance ?? 0).toFixed(1)} Credit
              </span>
            </div>

            {/* ⚠️ 6-Hour Warning — per spec verbatim */}
            <div className="p-3 rounded-xl bg-amber-50 border border-amber-300 space-y-1.5">
              <p className="text-xs font-bold text-amber-800 flex items-center gap-1.5">
                <AlertTriangle className="w-3.5 h-3.5 shrink-0" />
                ⚠️ تنبيه مهم
              </p>
              <p className="text-xs text-amber-700 leading-relaxed">
                صلاحية الرابط هي <strong>6 ساعات فقط</strong> من وقت الشراء، لذلك يجب استخدامه وتفعيل العرض خلال هذه المدة.
                نحن غير مسؤولين عن انتهاء صلاحية الرابط بسبب التأخر في استخدامه.
              </p>
            </div>

            {/* Maintenance warning */}
            {!isAvailable && (
              <div className="flex items-center gap-2 p-3 rounded-xl bg-amber-50 border border-amber-200">
                <Wrench className="w-4 h-4 text-amber-600 shrink-0" />
                <p className="text-xs text-amber-700">الخدمة تحت الصيانة حالياً.</p>
              </div>
            )}

            {/* Balance error */}
            {isAvailable && !hasEnoughBalance && (
              <div className="flex items-center gap-2 p-3 rounded-xl bg-destructive/5 border border-destructive/20">
                <AlertTriangle className="w-4 h-4 text-destructive shrink-0" />
                <p className="text-xs text-destructive">رصيد غير كافٍ. تواصل مع الدعم لشحن المحفظة.</p>
              </div>
            )}

            {/* Account warning */}
            <div className="flex items-start gap-2 p-3 rounded-xl bg-blue-50 border border-blue-200">
              <Shield className="w-4 h-4 text-blue-600 shrink-0 mt-0.5" />
              <p className="text-xs text-blue-700 leading-relaxed">
                تأكد من حساب Google الذي تريد التفعيل عليه قبل إتمام العملية. بعد تأكيد التفعيل وربط الاشتراك بالحساب، قد لا يمكن التراجع عن العملية.
              </p>
            </div>

            {/* Confirm checkbox */}
            <label className="flex items-start gap-2.5 cursor-pointer">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={e => setConfirmed(e.target.checked)}
                className="mt-0.5 w-4 h-4 rounded border-border accent-primary"
              />
              <span className="text-xs text-muted-foreground leading-relaxed">
                قرأت وفهمت التحذيرات أعلاه، وأوافق على المتابعة.
              </span>
            </label>

            {/* Submit */}
            <Button
              className="w-full h-12 text-base font-semibold gap-2"
              disabled={submitting || !isAvailable || !hasEnoughBalance || !confirmed || !!activeOrderId}
              onClick={handleConfirm}
            >
              {submitting
                ? <><Loader2 className="w-4 h-4 animate-spin" />جارٍ إرسال الطلب…</>
                : 'تأكيد الاشتراك'
              }
            </Button>

            {/* Active order notice */}
            {activeOrderId && (
              <div className="p-4 rounded-xl bg-amber-50 border border-amber-200 space-y-3">
                <div className="flex items-start gap-2">
                  <AlertTriangle className="w-4 h-4 text-amber-600 shrink-0 mt-0.5" />
                  <p className="text-xs text-amber-800 leading-relaxed">
                    يوجد طلب مفتوح من نفس الخدمة. يمكنك متابعته مباشرة.
                  </p>
                </div>
                <Button
                  variant="outline"
                  className="w-full h-10 text-sm"
                  onClick={() => navigate(`/store/orders/${activeOrderId}`)}
                >
                  متابعة الطلب المفتوح
                </Button>
              </div>
            )}

            {/* Failed order notice */}
            {failedOrderId && (
              <div className="p-4 rounded-xl bg-destructive/5 border border-destructive/20 space-y-3">
                <div className="flex items-start gap-2">
                  <AlertTriangle className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
                  <p className="text-xs text-destructive leading-relaxed">
                    تعذر إنشاء الطلب بسبب خطأ من المزود. يمكنك مراجعة الطلب الفاشل أو المحاولة بطلب جديد.
                  </p>
                </div>
                <div className="flex flex-col gap-2">
                  <Button
                    variant="outline"
                    className="w-full h-10 text-sm"
                    onClick={() => navigate(`/store/orders/${failedOrderId}`)}
                  >
                    عرض الطلب الفاشل
                  </Button>
                  <Button
                    className="w-full h-10 text-sm"
                    onClick={() => {
                      setFailedOrderId(null);
                      setIdempotencyKey(generateKey());
                      submittedRef.current = false;
                      toast.info('تم تجهيز طلب جديد. اضغط تأكيد الاشتراك.');
                    }}
                  >
                    إنشاء طلب جديد
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </CustomerLayout>
  );
}
