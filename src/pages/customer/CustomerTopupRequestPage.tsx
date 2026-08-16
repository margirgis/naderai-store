import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, MessageCircle, Clock, CheckCircle2, XCircle,
  AlertCircle, ArrowLeft, Phone, Zap, PackageOpen, Star, Tag,
  ScanLine, AlertTriangle, ShieldAlert, Search, CreditCard, ExternalLink,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import type { WalletTopupRequest } from '@/types/types';
import { toast } from 'sonner';

const DEFAULT_CREDIT_PRICE = 300;
const SUPPORT_PHONE = '201222692182';

interface CreditPackage {
  id: string;
  name: string;
  credits: number;
  price_per_credit: number;
  original_price_per_credit: number;
  discount_percent: number;
  total_price: number;
  expires_at: string | null;
  badge_text: string | null;
}

interface PaymentOrderSummary {
  order_id: string;
  order_number: number;
  status: string;
  credits_qty: number;
  expected_amount: number;
  expires_at: string;
  has_active: boolean;
}

// خريطة شاملة لحالات الطلب — تشمل scan_status أيضاً
const STATUS_CONFIG: Record<string, {
  label: string;
  icon: React.ElementType;
  colorClass: string;
}> = {
  pending:         { label: 'قيد المراجعة',       icon: Clock,         colorClass: 'bg-amber-500/10 text-amber-500' },
  scanning:        { label: 'جاري الفحص',          icon: ScanLine,      colorClass: 'bg-blue-500/10 text-blue-500' },
  rescanning:      { label: 'إعادة الفحص',         icon: ScanLine,      colorClass: 'bg-blue-400/10 text-blue-400' },
  approved:        { label: 'تمت الموافقة ✓',      icon: CheckCircle2,  colorClass: 'bg-green-500/10 text-green-500' },
  rejected:        { label: 'مرفوض',               icon: XCircle,       colorClass: 'bg-destructive/10 text-destructive' },
  not_found:       { label: 'لم يتم العثور',       icon: Search,        colorClass: 'bg-muted/40 text-muted-foreground' },
  amount_mismatch: { label: 'مبلغ غير مطابق',      icon: AlertTriangle, colorClass: 'bg-orange-500/10 text-orange-500' },
  failed:          { label: 'فشل الفحص',           icon: XCircle,       colorClass: 'bg-destructive/10 text-destructive' },
  duplicate:       { label: 'عملية مكررة',         icon: ShieldAlert,   colorClass: 'bg-purple-500/10 text-purple-500' },
};

// مولّد مفتاح idempotency للعميل (لمنع الإرسال المزدوج فقط — الـ fingerprint يأتي من Server)
function generateIdempotencyKey() {
  return `ik_${Date.now()}_${Math.random().toString(36).slice(2)}`;
}

export default function CustomerTopupRequestPage() {
  const navigate = useNavigate();
  const { profile } = useAuth();
  const [credits, setCredits] = useState('1');
  const [loading, setLoading] = useState(false);
  const [requests, setRequests] = useState<WalletTopupRequest[]>([]);
  const [fetching, setFetching] = useState(true);
  const [packages, setPackages] = useState<CreditPackage[]>([]);
  const [selectedPkg, setSelectedPkg] = useState<CreditPackage | null>(null);
  const [activeOrder, setActiveOrder] = useState<PaymentOrderSummary | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState(generateIdempotencyKey);
  const channelRef = useRef<ReturnType<typeof supabase.channel> | null>(null);
  const submittedRef = useRef(false);

  // حساب السعر المقدّر (للعرض فقط — الـ fingerprint يأتي من Server)
  const creditNum = Math.max(0, parseInt(credits) || 0);
  const valid = creditNum >= 1;
  const pricePerCredit = selectedPkg ? selectedPkg.price_per_credit : DEFAULT_CREDIT_PRICE;
  const originalPricePerCredit = selectedPkg ? selectedPkg.original_price_per_credit : DEFAULT_CREDIT_PRICE;
  const baseAmount = creditNum * pricePerCredit;
  const originalAmount = creditNum * originalPricePerCredit;
  const savedAmount = originalAmount - baseAmount;

  useEffect(() => {
    // تحميل العروض النشطة
    supabase
      .from('credit_packages')
      .select('*')
      .eq('is_active', true)
      .or('expires_at.is.null,expires_at.gt.' + new Date().toISOString())
      .order('sort_order')
      .then(({ data }) => setPackages((data ?? []) as CreditPackage[]));
  }, []);

  // جلب الطلب المفتوح الحالي للمستخدم
  const fetchActiveOrder = useCallback(async () => {
    if (!profile?.id) return;
    const { data } = await supabase.rpc('get_active_payment_order', { p_user_id: profile.id });
    const result = data as PaymentOrderSummary | null;
    setActiveOrder(result?.has_active ? result : null);
  }, [profile?.id]);

  useEffect(() => { fetchActiveOrder(); }, [fetchActiveOrder]);

  const load = useCallback(async () => {
    if (!profile?.id) return;
    setFetching(true);
    const { data } = await supabase
      .from('wallet_topup_requests')
      .select('*')
      .eq('customer_id', profile.id)
      .order('created_at', { ascending: false })
      .limit(20);
    setRequests((data ?? []) as WalletTopupRequest[]);
    setFetching(false);
  }, [profile?.id]);

  useEffect(() => { load(); }, [load]);

  // اشتراك Realtime لتحديث حالة الطلبات لحظياً
  useEffect(() => {
    if (!profile?.id) return;
    const ch = supabase
      .channel(`customer-topup-${profile.id}`)
      .on('postgres_changes', {
        event: 'UPDATE',
        schema: 'public',
        table: 'wallet_topup_requests',
        filter: `customer_id=eq.${profile.id}`,
      }, (payload) => {
        const updated = payload.new as WalletTopupRequest;
        setRequests((prev) =>
          prev.map((r) => (r.id === updated.id ? { ...r, ...updated } : r))
        );
        const scanStatus = (updated as any).scan_status as string;
        if (updated.status === 'approved') {
          toast.success('✅ تم تأكيد طلب شحن رصيدك!');
        } else if (scanStatus === 'amount_mismatch') {
          toast.warning('⚠️ المبلغ غير مطابق — تأكد من تحويل المبلغ بالقروش بالضبط.');
        } else if (scanStatus === 'not_found') {
          toast.info('لم يتم العثور على رسالة مطابقة. قد يستغرق بعض الوقت.');
        } else if (updated.status === 'rejected') {
          toast.error('تم رفض طلب الشحن. تواصل مع الدعم.');
        }
      })
      .subscribe();
    channelRef.current = ch;
    return () => { supabase.removeChannel(ch).catch(() => {}); };
  }, [profile?.id]);

  // عند اختيار عرض: ضبط الكريدت تلقائياً
  const handleSelectPackage = (pkg: CreditPackage) => {
    if (selectedPkg?.id === pkg.id) {
      setSelectedPkg(null);
    } else {
      setSelectedPkg(pkg);
      setCredits(String(pkg.credits));
    }
  };

  // إنشاء طلب شحن — Server يولد الـ fingerprint
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid || !profile?.id) return;
    if (submittedRef.current) { toast.error('جارٍ معالجة طلبك…'); return; }

    submittedRef.current = true;
    setLoading(true);

    try {
      const { data, error } = await supabase.functions.invoke('create-payment-order', {
        body: {
          credits_qty:       creditNum,
          offer_id:          selectedPkg?.id ?? null,
          idempotency_key:   idempotencyKey,
        },
      });

      if (error) {
        let msg = 'تعذر إنشاء الطلب. حاول مرة أخرى.';
        try {
          const t = await (error as any)?.context?.text?.();
          if (t) { const j = JSON.parse(t); msg = j.error ?? j.message ?? msg; }
        } catch { /**/ }
        toast.error(msg);
        submittedRef.current = false;
        return;
      }

      if (!data?.ok) {
        toast.error(data?.reason ?? 'تعذر إنشاء الطلب');
        submittedRef.current = false;
        return;
      }

      // إذا كان هناك طلب مفتوح مسبقاً → انتقل إليه
      if (data.has_active || data.idempotent) {
        toast.info('يوجد طلب شحن مفتوح. جارٍ الانتقال إليه…');
        navigate(`/store/wallet/payment/${data.order_id}`);
        return;
      }

      toast.success('تم إنشاء طلب الشحن. تحويل المبلغ المطلوب للإكمال.');
      navigate(`/store/wallet/payment/${data.order_id}`);
    } catch {
      toast.error('تعذر الاتصال بالخادم. حاول مرة أخرى.');
      submittedRef.current = false;
    } finally {
      setLoading(false);
    }
  };

  const waMsg = encodeURIComponent(`السلام عليكم، أنا ${profile?.email?.split('@')[0] ?? 'عميل'}\nأريد شراء ${creditNum || 1} Credit عبر فودافون كاش`);
  const waHref = `https://wa.me/${SUPPORT_PHONE}?text=${waMsg}`;

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 space-y-5 max-w-2xl mx-auto">
        <button onClick={() => navigate('/store/wallet')} className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="w-4 h-4" />
          العودة للمحفظة
        </button>

        <div className="space-y-1">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <Wallet className="w-5 h-5 text-primary" />
            طلب شحن رصيد
          </h1>
          <p className="text-sm text-muted-foreground">اختر عدد الكريدات والعرض المناسب، ثم أكمل التحويل.</p>
        </div>

        {/* بانر الطلب المفتوح */}
        {activeOrder && (
          <Card className="border-amber-300 bg-amber-50/70 shadow-sm">
            <CardContent className="pt-4 pb-4">
              <div className="flex items-start gap-3">
                <Clock className="w-5 h-5 text-amber-600 shrink-0 mt-0.5" />
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-amber-800">
                    يوجد طلب شحن مفتوح — طلب #{activeOrder.order_number}
                  </p>
                  <p className="text-xs text-amber-700 mt-0.5">
                    {activeOrder.credits_qty} Credit — المبلغ{' '}
                    <strong dir="ltr">{activeOrder.expected_amount.toFixed(2)} جنيه</strong>
                  </p>
                </div>
                <Button
                  size="sm"
                  className="shrink-0 h-8 text-xs gap-1.5"
                  onClick={() => navigate(`/store/wallet/payment/${activeOrder.order_id}`)}
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                  إكمال الدفع
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* العروض المتاحة */}
        {packages.length > 0 && (
          <Card className="bg-card border-border">
            <CardHeader className="pb-3">
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <Tag className="w-4 h-4 text-primary" />
                عروض وخصومات متاحة
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                {packages.map(pkg => {
                  const isSelected = selectedPkg?.id === pkg.id;
                  return (
                    <button
                      key={pkg.id}
                      type="button"
                      onClick={() => handleSelectPackage(pkg)}
                      className={`relative rounded-xl border-2 p-3 text-right transition-all ${
                        isSelected
                          ? 'border-primary bg-primary/10'
                          : 'border-border bg-muted/30 hover:border-primary/50 hover:bg-muted/60'
                      }`}
                    >
                      {pkg.badge_text && (
                        <span className="absolute -top-2.5 right-2 inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary text-primary-foreground">
                          <Star className="w-2.5 h-2.5" />
                          {pkg.badge_text}
                        </span>
                      )}
                      <p className="text-base font-bold text-foreground mt-1">{pkg.credits} كريدت</p>
                      <p className="text-lg font-extrabold text-primary">{pkg.total_price} جنيه</p>
                      <p className="text-xs text-muted-foreground line-through">{pkg.original_price_per_credit * pkg.credits} جنيه</p>
                      {Number(pkg.discount_percent) > 0 && (
                        <Badge className="mt-1 text-[10px] bg-green-500/15 text-green-600 border-green-200 hover:bg-green-500/15">
                          خصم {pkg.discount_percent}%
                        </Badge>
                      )}
                      {isSelected && (
                        <span className="absolute top-2 left-2 w-4 h-4 rounded-full bg-primary flex items-center justify-center">
                          <CheckCircle2 className="w-2.5 h-2.5 text-primary-foreground" />
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
              {selectedPkg && (
                <button
                  type="button"
                  onClick={() => setSelectedPkg(null)}
                  className="text-xs text-muted-foreground hover:text-foreground underline"
                >
                  إلغاء اختيار العرض (إدخال يدوي)
                </button>
              )}
            </CardContent>
          </Card>
        )}

        {/* نموذج إنشاء الطلب */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <PackageOpen className="w-4 h-4 text-primary" />
              بيانات الشحن
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label className="text-xs text-muted-foreground">عدد الكريدات</Label>
                <Input
                  type="number" min="1" step="1" value={credits}
                  onChange={(e) => {
                    setCredits(e.target.value);
                    if (selectedPkg && parseInt(e.target.value) !== selectedPkg.credits) {
                      setSelectedPkg(null);
                    }
                  }}
                  placeholder="1" className="text-center text-lg font-semibold"
                />
                <p className="text-xs text-muted-foreground">
                  سعر الكريدت:{' '}
                  {selectedPkg ? (
                    <>
                      <span className="line-through text-muted-foreground">{DEFAULT_CREDIT_PRICE}</span>
                      {' '}
                      <span className="text-primary font-semibold">{pricePerCredit} جنيه</span>
                    </>
                  ) : (
                    <span>{DEFAULT_CREDIT_PRICE} جنيه</span>
                  )}
                </p>
              </div>

              {valid && (
                <div className="p-3 rounded-lg bg-primary/5 border border-primary/20 space-y-2">
                  {savedAmount > 0 && (
                    <div className="flex items-center justify-between">
                      <span className="text-xs text-green-600 font-medium flex items-center gap-1">
                        <Zap className="w-3 h-3" />
                        توفير مع العرض
                      </span>
                      <span className="text-xs font-bold text-green-600">- {savedAmount.toFixed(0)} جنيه</span>
                    </div>
                  )}
                  <div className="flex items-center justify-between">
                    <span className="text-sm text-muted-foreground">المبلغ التقريبي</span>
                    <span className="text-sm font-medium">{baseAmount.toLocaleString('ar-SA')} جنيه</span>
                  </div>
                  <div className="flex items-center gap-2 pt-1">
                    <AlertCircle className="w-3.5 h-3.5 text-primary/70 shrink-0" />
                    <p className="text-xs text-muted-foreground leading-relaxed">
                      المبلغ الفعلي للتحويل (بالقروش) سيُحدَّد من الخادم بعد إنشاء الطلب.
                    </p>
                  </div>
                </div>
              )}

              <div className="p-3 rounded-lg border border-amber-500/30 bg-amber-500/5 text-xs flex items-start gap-2">
                <AlertCircle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
                <p className="text-muted-foreground">
                  <span className="font-semibold text-amber-500">مهم:</span> بعد إنشاء الطلب ستجد المبلغ الفعلي للتحويل بالقروش.
                  يجب التحويل بالضبط لضمان الموافقة التلقائية الفورية.
                </p>
              </div>

              <div className="flex flex-col gap-2">
                <Button
                  type="submit"
                  className="w-full h-11 font-semibold gap-2"
                  disabled={loading || !valid}
                >
                  {loading
                    ? <><Loader2 className="w-4 h-4 animate-spin" />جارٍ إنشاء الطلب…</>
                    : <><CreditCard className="w-4 h-4" />إنشاء طلب شحن</>
                  }
                </Button>
                <Button asChild variant="outline" className="w-full gap-2">
                  <a href={waHref} target="_blank" rel="noopener noreferrer">
                    <MessageCircle className="w-4 h-4" />
                    طلب مباشرة عبر واتساب
                  </a>
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        {/* السجل */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">طلباتي السابقة</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {fetching ? (
              <div className="flex justify-center py-8"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : requests.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">لا توجد طلبات سابقة</p>
            ) : (
              <div className="divide-y divide-border">
                {requests.map((r) => {
                  const scanStatus = (r as any).scan_status as string | undefined;
                  const displayKey = scanStatus && STATUS_CONFIG[scanStatus]
                    ? scanStatus
                    : (STATUS_CONFIG[r.status] ? r.status : 'pending');
                  const cfg = STATUS_CONFIG[displayKey];
                  const Icon = cfg.icon;
                  const cr = (r as any).credits_requested as number | null;
                  const fp = (r as any).fingerprint_amount as number | null;
                  const auto = (r as any).matched_automatically as boolean | null;
                  const failReason = r.failure_reason;
                  // استخراج payment_order_id من الملاحظات
                  const notesVal = (r as any).notes as string | null;
                  const orderIdMatch = notesVal?.match(/payment_order_id:([0-9a-f-]{36})/);
                  const paymentOrderId = orderIdMatch?.[1] ?? null;

                  return (
                    <div key={r.id} className="p-4 flex items-start justify-between gap-3">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-foreground">
                          {cr ? `${cr} Credit` : `${r.amount.toFixed(2)} جنيه`}
                        </p>
                        {fp && (
                          <p className="text-xs text-muted-foreground font-mono" dir="ltr">
                            المبلغ: {fp.toFixed(2)} جنيه
                          </p>
                        )}
                        <p className="text-xs text-muted-foreground">
                          {new Date(r.created_at).toLocaleString('ar-EG')}
                        </p>
                        {auto && (
                          <p className="text-xs text-green-500 flex items-center gap-1 mt-0.5">
                            <Zap className="w-3 h-3" /> موافقة تلقائية
                          </p>
                        )}
                        {failReason && (
                          <p className="text-xs text-orange-500 mt-1 flex items-start gap-1">
                            <AlertTriangle className="w-3 h-3 shrink-0 mt-0.5" />
                            {failReason}
                          </p>
                        )}
                        {/* رابط إكمال الدفع للطلبات المرتبطة بـ payment_order */}
                        {paymentOrderId && ['pending', 'scanning'].includes(r.status) && (
                          <button
                            type="button"
                            onClick={() => navigate(`/store/wallet/payment/${paymentOrderId}`)}
                            className="text-xs text-primary hover:underline flex items-center gap-1 mt-1"
                          >
                            <ExternalLink className="w-3 h-3" />
                            عرض تفاصيل الدفع
                          </button>
                        )}
                      </div>
                      <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium shrink-0 ${cfg.colorClass}`}>
                        <Icon className="w-3.5 h-3.5" />
                        {cfg.label}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </CustomerLayout>
  );
}
