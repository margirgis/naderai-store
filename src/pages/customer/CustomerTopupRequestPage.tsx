import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, MessageCircle, Clock, CheckCircle2, XCircle,
  AlertCircle, ArrowLeft, Phone, Zap, PackageOpen, Star, Tag,
  ScanLine, AlertTriangle, ShieldAlert, Search, CreditCard, ExternalLink,
  ChevronDown, ChevronUp, Info, MessageSquare,
} from 'lucide-react';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
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

// خريطة شاملة لحالات الطلب — تشمل status و scan_status معاً
// الأولوية: scan_status='duplicate' > status='rejected' (تمنع عرض "مرفوض" بدلاً من "مكرر")
const STATUS_CONFIG: Record<string, {
  label: string;
  icon: React.ElementType;
  colorClass: string;
}> = {
  pending:                  { label: 'قيد المراجعة',       icon: Clock,         colorClass: 'bg-amber-500/10 text-amber-500' },
  scanning:                 { label: 'جاري الفحص',          icon: ScanLine,      colorClass: 'bg-blue-500/10 text-blue-500' },
  rescanning:               { label: 'إعادة الفحص',         icon: ScanLine,      colorClass: 'bg-blue-400/10 text-blue-400' },
  waiting_for_verification: { label: 'جاري الفحص',          icon: ScanLine,      colorClass: 'bg-blue-500/10 text-blue-500' },
  approved:                 { label: 'تمت الموافقة ✓',      icon: CheckCircle2,  colorClass: 'bg-green-500/10 text-green-500' },
  confirmed:                { label: 'تمت الموافقة ✓',      icon: CheckCircle2,  colorClass: 'bg-green-500/10 text-green-500' },
  rejected:                 { label: 'مرفوض',               icon: XCircle,       colorClass: 'bg-destructive/10 text-destructive' },
  not_found:                { label: 'لم يتم العثور',       icon: Search,        colorClass: 'bg-muted/40 text-muted-foreground' },
  amount_mismatch:          { label: 'مبلغ غير مطابق',      icon: AlertTriangle, colorClass: 'bg-orange-500/10 text-orange-500' },
  failed:                   { label: 'فشل الفحص',           icon: XCircle,       colorClass: 'bg-destructive/10 text-destructive' },
  // scan_status=duplicate يغلب على status=rejected — يجب أن يظهر "عملية مكررة" لا "مرفوض"
  duplicate:                { label: 'عملية مكررة ⚠️',     icon: ShieldAlert,   colorClass: 'bg-purple-500/10 text-purple-500' },
  manual_review:            { label: 'قيد المراجعة اليدوية', icon: AlertCircle, colorClass: 'bg-amber-500/10 text-amber-500' },
};

// حساب displayKey الصحيح: scan_status=duplicate يأخذ الأولوية دائماً
function resolveDisplayKey(status: string, scanStatus?: string): string {
  if (scanStatus === 'duplicate')        return 'duplicate';
  if (scanStatus === 'amount_mismatch')  return 'amount_mismatch';
  if (scanStatus === 'not_found')        return 'not_found';
  if (scanStatus && STATUS_CONFIG[scanStatus]) return scanStatus;
  if (STATUS_CONFIG[status])             return status;
  return 'pending';
}

// مولّد مفتاح idempotency للعميل (لمنع الإرسال المزدوج فقط — الـ fingerprint يأتي من Server)
function generateIdempotencyKey() {
  return `ik_${Date.now()}_${Math.random().toString(36).slice(2)}`;
}

// ── Hook: Realtime مع إعادة الاتصال التلقائي ──────────────────────────────
function useRealtimeTopup(
  profileId: string | undefined,
  onUpdate: (updated: WalletTopupRequest) => void,
) {
  const channelRef    = useRef<ReturnType<typeof supabase.channel> | null>(null);
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const retryCountRef = useRef(0);
  const maxRetries    = 8;

  const connect = useCallback(() => {
    if (!profileId) return;
    // تنظيف القناة القديمة
    if (channelRef.current) {
      supabase.removeChannel(channelRef.current).catch(() => {});
    }

    const ch = supabase
      .channel(`customer-topup-rt-${profileId}-${Date.now()}`)
      .on('postgres_changes', {
        event: 'UPDATE',
        schema: 'public',
        table: 'wallet_topup_requests',
        filter: `customer_id=eq.${profileId}`,
      }, (payload) => {
        retryCountRef.current = 0; // اتصال ناجح — أعد عداد المحاولات
        onUpdate(payload.new as WalletTopupRequest);
      })
      .subscribe((status, err) => {
        if (status === 'SUBSCRIBED') {
          retryCountRef.current = 0;
        } else if (
          status === 'CHANNEL_ERROR' ||
          status === 'TIMED_OUT' ||
          status === 'CLOSED'
        ) {
          // انقطاع — أعد الاتصال مع backoff أسي
          if (retryCountRef.current < maxRetries) {
            const delay = Math.min(1000 * Math.pow(2, retryCountRef.current), 30000);
            retryCountRef.current++;
            if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
            retryTimerRef.current = setTimeout(() => connect(), delay);
          }
        }
      });
    channelRef.current = ch;
  }, [profileId, onUpdate]);

  useEffect(() => {
    connect();
    // إعادة الاتصال عند عودة الشبكة
    const handleOnline = () => { retryCountRef.current = 0; connect(); };
    window.addEventListener('online', handleOnline);
    return () => {
      window.removeEventListener('online', handleOnline);
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
      if (channelRef.current) supabase.removeChannel(channelRef.current).catch(() => {});
    };
  }, [connect]);
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
  const [historyTab, setHistoryTab] = useState<'open' | 'done' | 'all'>('all');
  const [detailRequest, setDetailRequest] = useState<WalletTopupRequest | null>(null);
  const submittedRef = useRef(false);

  // حساب السعر المقدّر (للعرض فقط — الـ fingerprint يأتي من Server)
  const creditNum = Math.max(0, parseInt(credits) || 0);
  const valid = creditNum >= 1;
  const pricePerCredit = selectedPkg ? selectedPkg.price_per_credit : DEFAULT_CREDIT_PRICE;
  const originalPricePerCredit = selectedPkg ? selectedPkg.original_price_per_credit : DEFAULT_CREDIT_PRICE;
  const baseAmount = creditNum * pricePerCredit;
  const originalAmount = creditNum * originalPricePerCredit;
  const savedAmount = originalAmount - baseAmount;

  // ── Handler لتحديث الطلبات من Realtime (stable ref) ─────────────
  const handleRealtimeUpdate = useCallback((updated: WalletTopupRequest) => {
    setRequests(prev => prev.map(r => r.id === updated.id ? { ...r, ...updated } : r));
    const scanStatus = (updated as any).scan_status as string;
    const key = resolveDisplayKey(updated.status, scanStatus);
    if (key === 'approved' || key === 'confirmed') {
      toast.success('✅ تم تأكيد طلب شحن رصيدك!');
    } else if (key === 'duplicate') {
      toast.error('⚠️ عملية مكررة — رقم العملية تم استخدامه مسبقاً.');
    } else if (key === 'amount_mismatch') {
      toast.warning('⚠️ المبلغ غير مطابق — تأكد من تحويل المبلغ بالقروش بالضبط.');
    } else if (key === 'not_found') {
      toast.info('لم يتم العثور على رسالة مطابقة. قد يستغرق بعض الوقت.');
    } else if (key === 'rejected') {
      toast.error('تم رفض طلب الشحن. تواصل مع الدعم.');
    }
  }, []);

  // ── Realtime مع إعادة الاتصال التلقائي ──────────────────────────
  useRealtimeTopup(profile?.id, handleRealtimeUpdate);

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
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-semibold">طلباتي السابقة</CardTitle>
            {/* Tabs */}
            <div className="flex gap-1 mt-2 bg-muted/40 rounded-lg p-1">
              {([
                { key: 'open',  label: 'مفتوحة' },
                { key: 'done',  label: 'مكتملة / ملغاة' },
                { key: 'all',   label: 'الكل' },
              ] as const).map(t => (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setHistoryTab(t.key)}
                  className={`flex-1 text-xs py-1.5 rounded-md font-medium transition-colors ${
                    historyTab === t.key
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {fetching ? (
              <div className="flex justify-center py-8">
                <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
              </div>
            ) : (() => {
              // terminal = scan_status takes priority (duplicate/amount_mismatch override rejected)
              const isTerminalStatus = (r: WalletTopupRequest) => {
                const ss = (r as any).scan_status as string | undefined;
                const key = resolveDisplayKey(r.status, ss);
                return ['approved','confirmed','rejected','failed','duplicate','not_found','amount_mismatch'].includes(key);
              };
              const filtered = requests.filter(r => {
                if (historyTab === 'open') return !isTerminalStatus(r);
                if (historyTab === 'done') return isTerminalStatus(r);
                return true;
              });
              if (filtered.length === 0) {
                return (
                  <p className="text-sm text-muted-foreground text-center py-8">
                    {historyTab === 'open' ? 'لا توجد طلبات مفتوحة' : historyTab === 'done' ? 'لا توجد طلبات مكتملة' : 'لا توجد طلبات سابقة'}
                  </p>
                );
              }
              return (
                <div className="divide-y divide-border">
                  {filtered.map((r) => {
                    const scanStatus = (r as any).scan_status as string | undefined;
                    // استخدام resolveDisplayKey بدلاً من المقارنة اليدوية — يضمن أن duplicate يظهر دائماً
                    const displayKey = resolveDisplayKey(r.status, scanStatus);
                    const cfg = STATUS_CONFIG[displayKey];
                    const Icon = cfg.icon;
                    const cr   = (r as any).credits_requested as number | null;
                    const fp   = (r as any).fingerprint_amount as number | null;
                    const auto = (r as any).matched_automatically as boolean | null;
                    const notesVal = (r as any).notes as string | null;
                    const orderIdMatch = notesVal?.match(/payment_order_id:([0-9a-f-]{36})/);
                    const paymentOrderId = orderIdMatch?.[1] ?? (r as any).payment_order_id ?? null;
                    const openStatuses = ['pending','scanning','waiting_for_verification','rescanning'];

                    return (
                      <div key={r.id} className="p-4 flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-medium text-foreground">
                            طلب شحن #{(r as any).order_number ?? r.id.slice(0,8)}
                          </p>
                          <p className="text-xs text-muted-foreground">
                            {cr ? `${cr} Credit` : `${r.amount.toFixed(2)} جنيه`}
                          </p>
                          {fp && (
                            <p className="text-xs text-muted-foreground font-mono" dir="ltr">
                              {fp.toFixed(2)} جنيه
                            </p>
                          )}
                          <p className="text-xs text-muted-foreground mt-0.5">
                            {new Date(r.created_at).toLocaleString('ar-EG')}
                          </p>
                          {auto && (
                            <p className="text-xs text-green-500 flex items-center gap-1 mt-0.5">
                              <Zap className="w-3 h-3" /> موافقة تلقائية
                            </p>
                          )}
                          {paymentOrderId && openStatuses.includes(r.status) && (
                            <button
                              type="button"
                              onClick={() => navigate(`/store/wallet/payment/${paymentOrderId}`)}
                              className="text-xs text-primary hover:underline flex items-center gap-1 mt-1"
                            >
                              <ExternalLink className="w-3 h-3" />
                              إكمال الدفع
                            </button>
                          )}
                        </div>
                        <div className="flex flex-col items-end gap-2 shrink-0">
                          <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium ${cfg.colorClass}`}>
                            <Icon className="w-3.5 h-3.5" />
                            {cfg.label}
                          </div>
                          <button
                            type="button"
                            onClick={() => setDetailRequest(r)}
                            className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                          >
                            <Info className="w-3 h-3" />
                            تفاصيل
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              );
            })()}
          </CardContent>
        </Card>

        {/* ── Modal تفاصيل الطلب ── */}
        <Dialog open={!!detailRequest} onOpenChange={() => setDetailRequest(null)}>
          <DialogContent className="max-w-[calc(100%-2rem)] md:max-w-md">
            <DialogHeader>
              <DialogTitle className="text-base font-bold">تفاصيل الطلب</DialogTitle>
            </DialogHeader>
            {detailRequest && (() => {
              const r = detailRequest;
              const scanStatus  = (r as any).scan_status as string | undefined;
              const displayKey  = resolveDisplayKey(r.status, scanStatus);
              const cfg  = STATUS_CONFIG[displayKey];
              const Icon = cfg.icon;
              const cr   = (r as any).credits_requested as number | null;
              const fp   = (r as any).fingerprint_amount as number | null;
              const auto = (r as any).matched_automatically as boolean | null;
              const smsBody     = (r as any).sms_body as string | null;
              const senderName  = (r as any).sender_name as string | null;
              const senderPhone = (r as any).sender_phone_confirmed as string | null;
              const txId        = (r as any).transaction_id as string | null;
              const notesVal    = (r as any).notes as string | null;
              const orderIdMatch = notesVal?.match(/payment_order_id:([0-9a-f-]{36})/);
              const paymentOrderId = orderIdMatch?.[1] ?? (r as any).payment_order_id ?? null;
              const openStatuses = ['pending','scanning','waiting_for_verification','rescanning'];

              const rows: { label: string; value: string; mono?: boolean }[] = [
                { label: 'رقم الطلب',     value: `#${(r as any).order_number ?? r.id.slice(0,8)}`, mono: true },
                { label: 'الكريدات',       value: cr ? `${cr} Credit` : '—' },
                { label: 'المبلغ التقريبي', value: `${r.amount.toFixed(2)} جنيه` },
                ...(fp ? [{ label: 'المبلغ بالقروش', value: `${fp.toFixed(2)} جنيه`, mono: true }] : []),
                { label: 'الحالة',         value: cfg.label },
                ...(txId ? [{ label: 'رقم العملية', value: txId, mono: true }] : []),
                ...(senderName  ? [{ label: 'اسم المحول',   value: senderName }] : []),
                ...(senderPhone ? [{ label: 'رقم المحول',   value: senderPhone, mono: true }] : []),
                { label: 'تاريخ الإنشاء', value: new Date(r.created_at).toLocaleString('ar-EG') },
                ...(auto ? [{ label: 'الموافقة', value: 'تلقائية ⚡' }] : []),
              ];

              // رسالة حسب النوع للحالات الغير ناجحة
              const failMsgMap: Record<string, string> = {
                not_found:       'لم يتم العثور على معاملة مطابقة. تأكد من إتمام التحويل ثم تواصل مع الدعم.',
                amount_mismatch: 'المبلغ المُحوَّل لا يطابق المطلوب. تواصل مع الدعم لمراجعة الطلب.',
                failed:          'حدث خطأ أثناء معالجة الطلب. تواصل مع الدعم.',
                rejected:        'تم رفض الطلب. تواصل مع الدعم للاستفسار.',
                duplicate:       'رقم العملية هذا تم استخدامه مسبقاً في طلب آخر. لا يمكن قبول نفس رقم العملية مرتين.',
              };
              const failMsg = failMsgMap[displayKey] ?? null;

              return (
                <div className="space-y-4">
                  {/* حالة */}
                  <div className={`flex items-center gap-2 px-3 py-2.5 rounded-lg text-sm font-medium ${cfg.colorClass}`}>
                    <Icon className="w-4 h-4 shrink-0" />
                    {cfg.label}
                  </div>

                  {/* جدول التفاصيل */}
                  <div className="divide-y divide-border rounded-lg border border-border overflow-hidden text-xs">
                    {rows.map(({ label, value, mono }) => (
                      <div key={label} className="flex items-center justify-between px-3 py-2 gap-2">
                        <span className="text-muted-foreground shrink-0">{label}</span>
                        <span className={`font-medium text-foreground text-left ${mono ? 'font-mono' : ''}`} dir="ltr">{value}</span>
                      </div>
                    ))}
                  </div>

                  {/* رسالة SMS الأصلية */}
                  {smsBody && (
                    <div className="space-y-1.5">
                      <p className="text-xs font-semibold text-muted-foreground flex items-center gap-1.5">
                        <MessageSquare className="w-3.5 h-3.5" />
                        رسالة الـ SMS المرصودة
                      </p>
                      <div className="p-3 rounded-lg bg-muted/40 border border-border">
                        <p className="text-xs text-foreground leading-relaxed font-mono break-all" dir="auto">
                          {smsBody}
                        </p>
                      </div>
                    </div>
                  )}

                  {/* سبب الرفض */}
                  {failMsg && (
                    <div className="flex items-start gap-2 p-3 rounded-lg bg-destructive/5 border border-destructive/20 text-xs text-destructive">
                      <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                      <p>{failMsg}</p>
                    </div>
                  )}

                  {/* رابط إكمال الدفع */}
                  {paymentOrderId && openStatuses.includes(r.status) && (
                    <button
                      type="button"
                      onClick={() => { setDetailRequest(null); navigate(`/store/wallet/payment/${paymentOrderId}`); }}
                      className="w-full flex items-center justify-center gap-2 py-2.5 rounded-lg bg-primary text-primary-foreground text-sm font-semibold hover:bg-primary/90 transition-colors"
                    >
                      <ExternalLink className="w-4 h-4" />
                      إكمال الدفع
                    </button>
                  )}
                </div>
              );
            })()}
          </DialogContent>
        </Dialog>
      </div>
    </CustomerLayout>
  );
}
