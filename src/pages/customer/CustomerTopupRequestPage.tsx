import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, MessageCircle, Clock, CheckCircle2, XCircle,
  AlertCircle, ArrowLeft, Copy, Check, Phone, Zap, PackageOpen, Star, Tag,
  ScanLine, AlertTriangle, ShieldAlert, Search,
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
const DEFAULT_VF_NUMBER = '01097273680';

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

function generateFingerprint(): number {
  return Math.round((Math.random() * 0.98 + 0.01) * 100) / 100;
}

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

export default function CustomerTopupRequestPage() {
  const navigate = useNavigate();
  const { profile } = useAuth();
  const [credits, setCredits] = useState('1');
  const [senderPhone, setSenderPhone] = useState('');
  const [senderName, setSenderName] = useState('');
  const [loading, setLoading] = useState(false);
  const [requests, setRequests] = useState<WalletTopupRequest[]>([]);
  const [fetching, setFetching] = useState(true);
  const [vfNumber, setVfNumber] = useState(DEFAULT_VF_NUMBER);
  const [fingerprint, setFingerprint] = useState(() => generateFingerprint());
  const [packages, setPackages] = useState<CreditPackage[]>([]);
  const [selectedPkg, setSelectedPkg] = useState<CreditPackage | null>(null);
  const channelRef = useRef<ReturnType<typeof supabase.channel> | null>(null);

  // حساب السعر: إذا اختار عرض يُطبّق سعره، وإلا السعر الافتراضي
  const creditNum = Math.max(0, parseInt(credits) || 0);
  const valid = creditNum >= 1;
  const pricePerCredit = selectedPkg ? selectedPkg.price_per_credit : DEFAULT_CREDIT_PRICE;
  const originalPricePerCredit = selectedPkg ? selectedPkg.original_price_per_credit : DEFAULT_CREDIT_PRICE;
  const baseAmount = creditNum * pricePerCredit;
  const originalAmount = creditNum * originalPricePerCredit;
  const savedAmount = originalAmount - baseAmount;
  const totalAmount = valid ? parseFloat((baseAmount + fingerprint).toFixed(2)) : 0;
  const totalStr = totalAmount.toFixed(2);

  useEffect(() => {
    supabase.from('system_settings').select('value').eq('key', 'vodafone_cash_number').maybeSingle()
      .then(({ data }) => { if (data?.value) setVfNumber(data.value); });
    // تحميل العروض النشطة
    supabase
      .from('credit_packages')
      .select('*')
      .eq('is_active', true)
      .or('expires_at.is.null,expires_at.gt.' + new Date().toISOString())
      .order('sort_order')
      .then(({ data }) => setPackages((data ?? []) as CreditPackage[]));
  }, []);

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
      setFingerprint(generateFingerprint());
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid || !profile?.id) return;
    setLoading(true);
    const { error } = await supabase.from('wallet_topup_requests').insert({
      customer_id: profile.id,
      amount: totalAmount,
      credits_requested: creditNum,
      fingerprint_amount: totalAmount,
      sender_phone: senderPhone.trim() || null,
      sender_name: senderName.trim() || null,
      payment_method: 'vodafone_cash',
      package_id: selectedPkg?.id ?? null,
    });
    if (error) {
      toast.error('فشل إرسال طلب الشحن. حاول مرة أخرى.');
    } else {
      toast.success('تم إرسال طلب الشحن. يُرجى تحويل المبلغ بالقروش بالضبط.');
      setCredits('1');
      setSenderPhone('');
      setSenderName('');
      setSelectedPkg(null);
      setFingerprint(generateFingerprint());
      await load();
    }
    setLoading(false);
  };

  const waMsg = encodeURIComponent(`السلام عليكم، أنا ${profile?.email?.split('@')[0] ?? 'عميل'}\nأريد شراء ${creditNum || 1} Credit عبر فودافون كاش\nالإجمالي: ${totalStr} جنيه مصري`);
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
          <p className="text-sm text-muted-foreground">حوّل المبلغ بالقروش بالضبط لضمان التفعيل الفوري والتلقائي.</p>
        </div>

        {/* رقم فودافون كاش */}
        <Card className="bg-card border-border">
          <CardContent className="pt-4 pb-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                  <Phone className="w-3.5 h-3.5" />
                  رقم فودافون كاش الرسمي للتحويل
                </p>
                <p className="text-xl font-bold tracking-widest text-primary" dir="ltr">{vfNumber}</p>
              </div>
              <CopyButton value={vfNumber} label="نسخ الرقم" />
            </div>
          </CardContent>
        </Card>

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
                          <Check className="w-2.5 h-2.5 text-primary-foreground" />
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
              {selectedPkg && (
                <button
                  type="button"
                  onClick={() => { setSelectedPkg(null); }}
                  className="text-xs text-muted-foreground hover:text-foreground underline"
                >
                  إلغاء اختيار العرض (إدخال يدوي)
                </button>
              )}
            </CardContent>
          </Card>
        )}

        {/* نموذج الشحن */}
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
                    setFingerprint(generateFingerprint());
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
                    <span className="text-sm text-muted-foreground">المبلغ الأساسي</span>
                    <span className="text-sm font-medium">{baseAmount.toLocaleString('ar-SA')} جنيه</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-muted-foreground flex items-center gap-1">
                      <Zap className="w-3 h-3 text-amber-500" />
                      قيمة التعرف التلقائي
                    </span>
                    <span className="text-xs font-mono text-amber-500">+ {fingerprint.toFixed(2)} جنيه</span>
                  </div>
                  <div className="border-t border-primary/20 pt-2 flex items-center justify-between">
                    <span className="text-sm font-semibold text-foreground">المبلغ الإجمالي</span>
                    <div className="flex items-center gap-2">
                      <span className="text-lg font-bold text-primary" dir="ltr">{totalStr} جنيه</span>
                      <CopyButton value={totalStr} />
                    </div>
                  </div>
                </div>
              )}

              <div className="space-y-2">
                <Label className="text-xs text-muted-foreground">رقم فودافون كاش المُحوِّل (رقمك)</Label>
                <Input type="tel" value={senderPhone} onChange={(e) => setSenderPhone(e.target.value)} placeholder="01012345678" dir="ltr" required />
              </div>

              <div className="space-y-2">
                <Label className="text-xs text-muted-foreground">اسم صاحب المحفظة (موصى به)</Label>
                <Input value={senderName} onChange={(e) => setSenderName(e.target.value)} placeholder="الاسم الأول على الأقل" />
              </div>

              <div className="p-3 rounded-lg border border-amber-500/30 bg-amber-500/5 text-xs flex items-start gap-2">
                <AlertCircle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
                <p className="text-muted-foreground">
                  <span className="font-semibold text-amber-500">مهم جداً:</span> يجب تحويل المبلغ بالقروش بالضبط ({totalStr} جنيه) لضمان الموافقة التلقائية الفورية.
                </p>
              </div>

              <div className="flex flex-col gap-2">
                <Button type="submit" className="w-full" disabled={loading || !valid || !senderPhone.trim()}>
                  {loading && <Loader2 className="w-4 h-4 ms-2 animate-spin" />}
                  {loading ? 'جارٍ إرسال الطلب...' : 'إرسال طلب الشحن'}
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
                  // الحالة الفعلية: scan_status يأخذ الأولوية على status لعرض أدق
                  const displayKey = scanStatus && STATUS_CONFIG[scanStatus]
                    ? scanStatus
                    : (STATUS_CONFIG[r.status] ? r.status : 'pending');
                  const cfg = STATUS_CONFIG[displayKey];
                  const Icon = cfg.icon;
                  const cr = (r as any).credits_requested as number | null;
                  const fp = (r as any).fingerprint_amount as number | null;
                  const auto = (r as any).matched_automatically as boolean | null;
                  const failReason = r.failure_reason;

                  return (
                    <div key={r.id} className="p-4 flex items-start justify-between gap-3">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-foreground">
                          {cr ? `${cr} Credit` : `${r.amount.toFixed(2)} Credit`}
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

