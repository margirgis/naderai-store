import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, MessageCircle, Clock, CheckCircle2, XCircle,
  AlertCircle, ArrowLeft, Copy, Check, Phone, Zap,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import type { WalletTopupRequest } from '@/types/types';
import { toast } from 'sonner';

const CREDIT_PRICE_EGP = 300;
const SUPPORT_PHONE = '201222692182';
const DEFAULT_VF_NUMBER = '01097273680';

const STATUS_LABELS: Record<string, string> = {
  pending: 'قيد المراجعة',
  approved: 'تمت الموافقة',
  rejected: 'مرفوض',
};

const STATUS_ICONS = { pending: Clock, approved: CheckCircle2, rejected: XCircle };

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

  const creditNum = Math.max(0, parseInt(credits) || 0);
  const valid = creditNum >= 1;
  const baseAmount = creditNum * CREDIT_PRICE_EGP;
  const totalAmount = valid ? parseFloat((baseAmount + fingerprint).toFixed(2)) : 0;
  const totalStr = totalAmount.toFixed(2);

  useEffect(() => {
    supabase.from('system_settings').select('value').eq('key', 'vodafone_cash_number').maybeSingle()
      .then(({ data }) => { if (data?.value) setVfNumber(data.value); });
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
    });
    if (error) {
      toast.error('فشل إرسال طلب الشحن. حاول مرة أخرى.');
    } else {
      toast.success('تم إرسال طلب الشحن. يُرجى تحويل المبلغ بالقروش بالضبط.');
      setCredits('1');
      setSenderPhone('');
      setSenderName('');
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

        {/* Official VF Number */}
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

        {/* Top-up form */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">بيانات الشحن</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label className="text-xs text-muted-foreground">عدد الكريدات</Label>
                <Input
                  type="number" min="1" step="1" value={credits}
                  onChange={(e) => { setCredits(e.target.value); setFingerprint(generateFingerprint()); }}
                  placeholder="1" className="text-center text-lg font-semibold"
                />
                <p className="text-xs text-muted-foreground">سعر الكريدت الواحد: {CREDIT_PRICE_EGP} جنيه</p>
              </div>

              {valid && (
                <div className="p-3 rounded-lg bg-primary/5 border border-primary/20 space-y-2">
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

        {/* History */}
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
                  const Icon = STATUS_ICONS[r.status as keyof typeof STATUS_ICONS] ?? Clock;
                  const cr = (r as any).credits_requested as number | null;
                  const fp = (r as any).fingerprint_amount as number | null;
                  const auto = (r as any).matched_automatically as boolean | null;
                  return (
                    <div key={r.id} className="p-4 flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-foreground">{cr ? `${cr} Credit` : `${r.amount.toFixed(2)} Credit`}</p>
                        {fp && <p className="text-xs text-muted-foreground font-mono" dir="ltr">المبلغ: {fp.toFixed(2)} جنيه</p>}
                        <p className="text-xs text-muted-foreground">{new Date(r.created_at).toLocaleString('ar-SA')}</p>
                        {auto && <p className="text-xs text-green-500 flex items-center gap-1 mt-0.5"><Zap className="w-3 h-3" /> موافقة تلقائية</p>}
                      </div>
                      <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium shrink-0
                        ${r.status === 'approved' ? 'bg-green-500/10 text-green-500' :
                          r.status === 'rejected' ? 'bg-destructive/10 text-destructive' : 'bg-amber-500/10 text-amber-500'}`}>
                        <Icon className="w-3.5 h-3.5" />
                        {STATUS_LABELS[r.status] ?? r.status}
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
