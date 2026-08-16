import React, { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowRight, Loader2, AlertCircle, CheckCircle2, Clock,
  Package, Send, Cog, XCircle, Copy, Check, ExternalLink,
  ChevronRight, AlertTriangle,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { OrderStatusBadge } from '@/components/customer/OrderStatusBadge';
import type { Order } from '@/types/types';
import { toast } from 'sonner';

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between py-2.5 border-b border-border/50 last:border-0 gap-4">
      <span className="text-xs text-muted-foreground shrink-0">{label}</span>
      <span className="text-sm text-foreground text-end min-w-0 break-words">{value}</span>
    </div>
  );
}

function InlineCopy({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const handle = async () => {
    try {
      if (navigator.clipboard) { await navigator.clipboard.writeText(text); }
      else {
        const ta = document.createElement('textarea');
        ta.value = text; document.body.appendChild(ta); ta.select();
        document.execCommand('copy'); document.body.removeChild(ta);
      }
      setCopied(true); setTimeout(() => setCopied(false), 2500);
    } catch { toast.error('تعذر النسخ'); }
  };
  return (
    <button onClick={handle}
      className="flex items-center gap-1.5 text-xs text-primary hover:underline shrink-0">
      {copied ? <><Check className="w-3.5 h-3.5 text-green-600" />تم النسخ</> : <><Copy className="w-3.5 h-3.5" />نسخ</>}
    </button>
  );
}

type TimelineStep = { icon: React.ElementType; label: string; done: boolean; active: boolean };
function Timeline({ steps }: { steps: TimelineStep[] }) {
  return (
    <div className="flex flex-col gap-0">
      {steps.map(({ icon: Icon, label, done, active }, i) => (
        <div key={label} className="flex items-start gap-3">
          <div className="flex flex-col items-center">
            <div className={['w-7 h-7 rounded-full flex items-center justify-center shrink-0',
              done ? 'bg-green-500' : active ? 'bg-primary' : 'bg-muted'].join(' ')}>
              <Icon className={`w-3.5 h-3.5 ${done || active ? 'text-white' : 'text-muted-foreground'}`} />
            </div>
            {i < steps.length - 1 && (
              <div className={`w-0.5 h-5 mt-0.5 ${done ? 'bg-green-200' : 'bg-border'}`} />
            )}
          </div>
          <p className={`pt-1 text-sm ${done ? 'text-green-700 font-medium' : active ? 'text-primary font-medium' : 'text-muted-foreground'}`}>
            {label}
          </p>
        </div>
      ))}
    </div>
  );
}

/* Countdown display — pure render helper */
function CountdownBadge({ linkTimestamp }: { linkTimestamp: string }) {
  const VALID_HOURS = 6;
  const [remaining, setRemaining] = useState<number | null>(null);

  useEffect(() => {
    const expiresAt = new Date(linkTimestamp).getTime() + VALID_HOURS * 60 * 60 * 1000;
    const tick = () => { const d = expiresAt - Date.now(); setRemaining(d > 0 ? d : 0); };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [linkTimestamp]);

  if (remaining === null) return null;
  if (remaining === 0) return (
    <span className="text-xs text-destructive font-medium">انتهت الصلاحية</span>
  );
  const h = Math.floor(remaining / 3_600_000);
  const m = Math.floor((remaining % 3_600_000) / 60_000);
  const s = Math.floor((remaining % 60_000) / 1_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    <span className="text-xs font-mono font-bold text-primary tabular-nums">
      {pad(h)}:{pad(m)}:{pad(s)}
    </span>
  );
}

export default function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<Order | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!orderId) return;
    const { data } = await supabase
      .from('orders')
      .select('*, provider_services!service_id(name, display_name_ar, input_type)')
      .eq('id', orderId)
      .maybeSingle();
    setOrder(data as unknown as Order ?? null);
    setLoading(false);
  }, [orderId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!orderId) return;
    const ch = supabase.channel(`order-detail:${orderId}`)
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'orders', filter: `id=eq.${orderId}` },
        (payload) => { setOrder(prev => prev ? { ...prev, ...payload.new } : prev); })
      .subscribe();
    return () => { supabase.removeChannel(ch); };
  }, [orderId]);

  if (loading) return (
    <CustomerLayout><div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div></CustomerLayout>
  );
  if (!order) return (
    <CustomerLayout>
      <div className="flex flex-col items-center py-16 gap-3">
        <AlertCircle className="w-8 h-8 text-destructive" />
        <p className="text-sm text-muted-foreground">الطلب غير موجود</p>
        <Button size="sm" variant="secondary" onClick={() => navigate('/store/orders')}>العودة</Button>
      </div>
    </CustomerLayout>
  );

  const isTerminal = ['success', 'partial', 'failed', 'cancelled', 'rejected'].includes(order.status);
  const svc = (order as any).provider_services;
  const svcName = svc?.display_name_ar ?? svc?.name ?? order.provider_service_code;
  const result = order.result_data as Record<string, unknown> | null;
  const offerLink = order.offer_link ?? (result?.offer_link as string) ?? (result?.two_fa_link as string) ?? null;
  const safeError = order.safe_error_message ?? null;
  const linkTimestamp = (order as any).offer_link_created_at ?? null;

  const timelineSteps: TimelineStep[] = [
    { icon: Package,   label: 'تم إنشاء الطلب',             done: true,   active: false },
    { icon: Send,      label: 'تم إرسال الطلب للمزود',       done: !['creating'].includes(order.status), active: order.status === 'queued' },
    { icon: Cog,       label: 'قيد التنفيذ',                 done: isTerminal, active: order.status === 'processing' },
    { icon: order.status === 'failed' || order.status === 'rejected' ? XCircle : CheckCircle2,
      label: order.status === 'failed' ? 'فشل الطلب' : order.status === 'rejected' ? 'تم الرفض' : 'مكتمل',
      done: isTerminal, active: false },
  ];

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-lg mx-auto space-y-5">
        <button onClick={() => navigate('/store/orders')}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ArrowRight className="w-4 h-4" />
          العودة لطلباتي
        </button>

        <div className="flex items-start justify-between gap-3">
          <div className="space-y-0.5 min-w-0">
            <h1 className="text-lg font-bold text-foreground">تفاصيل الطلب</h1>
            <p className="text-xs font-mono text-muted-foreground">{order.reference}</p>
          </div>
          <OrderStatusBadge status={order.status} />
        </div>

        {!isTerminal && (
          <div className="flex items-center gap-2 text-sm text-primary p-3 rounded-xl bg-primary/5 border border-primary/20">
            <Clock className="w-4 h-4 shrink-0 animate-pulse" />
            طلبك قيد المعالجة — يتم التحديث تلقائياً.
          </div>
        )}

        {/* Timeline */}
        <Card className="bg-card border-border shadow-sm">
          <CardHeader className="pb-3"><CardTitle className="text-sm font-semibold">مراحل الطلب</CardTitle></CardHeader>
          <CardContent><Timeline steps={timelineSteps} /></CardContent>
        </Card>

        {/* ── Offer link CTA card — primary action ─────────────────── */}
        {offerLink && (
          <Card className="overflow-hidden border-green-200 shadow-sm">
            <div className="h-1.5 bg-green-500" />
            <CardContent className="p-4 space-y-3">
              <div className="flex items-start justify-between gap-2">
                <div className="space-y-0.5">
                  <p className="text-sm font-bold text-green-700">🎁 رابط تفعيل الاشتراك جاهز!</p>
                  {linkTimestamp && (
                    <div className="flex items-center gap-2">
                      <Clock className="w-3 h-3 text-muted-foreground" />
                      <span className="text-xs text-muted-foreground">الصلاحية:</span>
                      <CountdownBadge linkTimestamp={linkTimestamp} />
                    </div>
                  )}
                </div>
              </div>

              {/* Short link preview — truncated */}
              <div className="p-2.5 bg-green-50 border border-green-200 rounded-lg">
                <p className="text-xs font-mono text-green-800 truncate">{offerLink}</p>
              </div>

              {/* Copy inline */}
              <div className="flex items-center gap-2">
                <InlineCopy text={offerLink} />
                <span className="text-muted-foreground text-xs">|</span>
                <a href={offerLink} target="_blank" rel="noopener noreferrer"
                  className="flex items-center gap-1 text-xs text-primary hover:underline">
                  <ExternalLink className="w-3.5 h-3.5" />فتح الرابط
                </a>
              </div>

              {/* CTA to dedicated page */}
              <Link to={`/store/orders/${orderId}/activation`}>
                <Button className="w-full gap-2 font-semibold">
                  عرض رابط التفعيل الكامل
                  <ChevronRight className="w-4 h-4" />
                </Button>
              </Link>
            </CardContent>
          </Card>
        )}

        {/* Order info */}
        <Card className="bg-card border-border shadow-sm">
          <CardContent className="p-4 space-y-0">
            <InfoRow label="الخدمة"        value={svcName} />
            <InfoRow label="المدة"          value="18 Months (540 Days)" />
            <InfoRow label="السعر"          value={<span className="text-primary font-semibold">{(order.customer_total ?? 0).toFixed(1)} Credit</span>} />
            <InfoRow label="تاريخ الإنشاء"  value={new Date(order.created_at).toLocaleString('ar-SA')} />
            {(order as any).updated_at && (
              <InfoRow label="آخر تحديث"    value={new Date((order as any).updated_at).toLocaleString('ar-SA')} />
            )}
            {order.completed_at && (
              <InfoRow label="وقت الإكمال"  value={new Date(order.completed_at).toLocaleString('ar-SA')} />
            )}
          </CardContent>
        </Card>

        {/* Failed reason */}
        {(order.status === 'failed' || order.status === 'rejected') && (
          <Card className="bg-destructive/5 border-destructive/20 shadow-sm">
            <CardContent className="p-4 flex items-start gap-3">
              <AlertTriangle className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-medium text-destructive">تعذر تنفيذ الطلب</p>
                <p className="text-xs text-muted-foreground mt-1">
                  {safeError ?? 'تعذر تنفيذ هذا الطلب. تواصل مع الدعم إذا تكرر الأمر.'}
                </p>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </CustomerLayout>
  );
}
