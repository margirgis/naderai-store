import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowRight, Copy, Check, ExternalLink, AlertTriangle,
  Clock, Loader2, AlertCircle, Shield, ChevronDown, ChevronUp,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import type { Order } from '@/types/types';
import { toast } from 'sonner';

/* ── Countdown hook (based on server timestamp) ─────────────────── */
function useCountdown(createdAt: string | null | undefined) {
  const VALID_HOURS = 6;
  const [remaining, setRemaining] = useState<number | null>(null);

  useEffect(() => {
    if (!createdAt) return;
    const expiresAt = new Date(createdAt).getTime() + VALID_HOURS * 60 * 60 * 1000;
    const tick = () => {
      const now = Date.now();
      const diff = expiresAt - now;
      setRemaining(diff > 0 ? diff : 0);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [createdAt]);

  if (remaining === null) return { expired: false, display: null };
  if (remaining === 0) return { expired: true, display: '00:00:00' };

  const h = Math.floor(remaining / 3_600_000);
  const m = Math.floor((remaining % 3_600_000) / 60_000);
  const s = Math.floor((remaining % 60_000) / 1_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return { expired: false, display: `${pad(h)}:${pad(m)}:${pad(s)}` };
}

/* ── Copy button ──────────────────────────────────────────────────── */
function CopyLinkButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const handle = async () => {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setCopied(true);
      toast.success('✓ تم نسخ الرابط');
      setTimeout(() => setCopied(false), 3000);
    } catch {
      toast.error('تعذر النسخ — انسخ الرابط يدوياً');
    }
  };
  return (
    <Button
      onClick={handle}
      className="w-full gap-2 h-12 text-base font-semibold"
      variant={copied ? 'secondary' : 'default'}
    >
      {copied
        ? <><Check className="w-5 h-5 text-green-600" />✓ تم نسخ الرابط</>
        : <><Copy className="w-5 h-5" />📋 نسخ الرابط</>
      }
    </Button>
  );
}

/* ── ActivationLinkPage ─────────────────────────────────────────── */
export default function ActivationLinkPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const { profile } = useAuth();
  const [order, setOrder] = useState<Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [showSteps, setShowSteps] = useState(true);

  const load = useCallback(async () => {
    if (!orderId) return;
    const { data } = await supabase
      .from('orders')
      .select('id, reference, status, offer_link, offer_link_created_at, created_at, customer_id, result_data')
      .eq('id', orderId)
      .maybeSingle();
    setOrder(data as unknown as Order ?? null);
    setLoading(false);
  }, [orderId]);

  useEffect(() => { load(); }, [load]);

  // Realtime: update offer_link when it arrives
  useEffect(() => {
    if (!orderId) return;
    const ch = supabase.channel(`activation:${orderId}`)
      .on('postgres_changes', {
        event: 'UPDATE', schema: 'public', table: 'orders',
        filter: `id=eq.${orderId}`,
      }, (payload) => {
        setOrder(prev => prev ? { ...prev, ...payload.new } : prev);
      })
      .subscribe();
    return () => { supabase.removeChannel(ch); };
  }, [orderId]);

  // ── Hooks MUST be called unconditionally before any early return ──
  // Resolve timestamp from order (null when order not yet loaded)
  const linkTimestamp: string | null = order
    ? ((order as any).offer_link_created_at ?? null)
    : null;
  // useCountdown is always called — it handles null gracefully
  const { expired, display: countdownDisplay } = useCountdown(linkTimestamp);

  // ── Early returns after all hooks ────────────────────────────────
  if (loading) return (
    <CustomerLayout>
      <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
    </CustomerLayout>
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

  // Customer isolation check (belt & suspenders — RLS handles server-side)
  if (order.customer_id !== profile?.id) {
    return (
      <CustomerLayout>
        <div className="flex flex-col items-center py-16 gap-3">
          <AlertCircle className="w-8 h-8 text-destructive" />
          <p className="text-sm text-muted-foreground">غير مصرح</p>
        </div>
      </CustomerLayout>
    );
  }

  // Resolve offer link: check order.offer_link then result_data
  const result = order.result_data as Record<string, unknown> | null;
  const offerLink = order.offer_link ?? (result?.offer_link as string) ?? (result?.two_fa_link as string) ?? null;

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-lg mx-auto space-y-5">
        {/* Back */}
        <button onClick={() => navigate(`/store/orders/${orderId}`)}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ArrowRight className="w-4 h-4" />
          العودة لتفاصيل الطلب
        </button>

        {/* Title */}
        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground">🎁 رابط تفعيل الاشتراك</h1>
          <p className="text-xs font-mono text-muted-foreground">{order.reference}</p>
        </div>

        {/* No link yet */}
        {!offerLink ? (
          <Card className="bg-card border-border shadow-sm">
            <CardContent className="p-6 flex flex-col items-center gap-3 text-center">
              <Clock className="w-8 h-8 text-primary animate-pulse" />
              <p className="text-sm font-medium text-foreground">رابط التفعيل لم يصل بعد</p>
              <p className="text-xs text-muted-foreground">يتم معالجة طلبك. سيصلك إشعار عند توفر الرابط.</p>
              <Button size="sm" variant="outline" onClick={load} className="gap-1.5 mt-1">
                <Loader2 className="w-3.5 h-3.5" />تحقق الآن
              </Button>
            </CardContent>
          </Card>
        ) : (
          <>
            {/* Countdown */}
            {countdownDisplay && (
              <div className={`flex items-center justify-between p-4 rounded-xl border ${
                expired
                  ? 'bg-destructive/5 border-destructive/30'
                  : 'bg-primary/5 border-primary/20'
              }`}>
                <div className="flex items-center gap-2">
                  <Clock className={`w-4 h-4 ${expired ? 'text-destructive' : 'text-primary'}`} />
                  <span className="text-sm font-medium text-foreground">
                    {expired ? 'انتهت صلاحية رابط التفعيل' : 'صلاحية رابط التفعيل'}
                  </span>
                </div>
                {!expired && (
                  <span className="text-xl font-mono font-bold text-primary tabular-nums">
                    {countdownDisplay}
                  </span>
                )}
              </div>
            )}

            {/* Expired warning */}
            {expired && (
              <div className="p-3 rounded-xl bg-destructive/5 border border-destructive/20 flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
                <p className="text-xs text-destructive leading-relaxed">
                  انتهت صلاحية هذا الرابط. لا تستخدمه. تواصل مع الدعم إذا لزم الأمر.
                </p>
              </div>
            )}

            {/* Offer link card */}
            <Card className={`shadow-sm overflow-hidden ${expired ? 'border-destructive/20' : 'border-green-200'}`}>
              {!expired && <div className="h-1 bg-green-500" />}
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-semibold text-foreground">رابط التفعيل</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {/* Link display — full URL, no truncation, breaks safely */}
                <div className={`p-4 rounded-xl border text-xs font-mono leading-relaxed ${
                  expired
                    ? 'bg-muted/30 border-border text-muted-foreground line-through'
                    : 'bg-green-50 border-green-200 text-green-900'
                }`}
                  style={{ overflowWrap: 'anywhere', wordBreak: 'break-all' }}>
                  {offerLink}
                </div>

                {!expired && (
                  <div className="flex flex-col gap-2">
                    <CopyLinkButton text={offerLink} />
                    <a
                      href={offerLink}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="flex items-center justify-center gap-2 text-sm text-primary hover:underline py-2"
                    >
                      <ExternalLink className="w-4 h-4" />
                      فتح الرابط مباشرة
                    </a>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Security notice */}
            <div className="flex items-start gap-2 p-3 rounded-xl bg-destructive/5 border border-destructive/20">
              <Shield className="w-4 h-4 text-destructive shrink-0 mt-0.5" />
              <div className="space-y-0.5">
                <p className="text-xs font-semibold text-destructive">⚠️ لا تشارك رابط التفعيل مع أي شخص.</p>
                <p className="text-xs text-muted-foreground">هذا الرابط خاص بك ومرتبط بحسابك فقط.</p>
              </div>
            </div>

            {/* Activation steps — collapsible */}
            <Card className="bg-card border-border shadow-sm">
              <button
                className="w-full flex items-center justify-between px-4 py-3 text-right"
                onClick={() => setShowSteps(s => !s)}
              >
                <span className="text-sm font-semibold text-foreground">طريقة تفعيل الاشتراك</span>
                {showSteps ? <ChevronUp className="w-4 h-4 text-muted-foreground" /> : <ChevronDown className="w-4 h-4 text-muted-foreground" />}
              </button>
              {showSteps && (
                <CardContent className="pt-0 pb-4 px-4 space-y-2">
                  <ol className="space-y-2.5">
                    {[
                      'اضغط "📋 نسخ الرابط" أعلاه.',
                      'افتح المتصفح (Chrome / Safari).',
                      'سجّل الدخول بحساب Google الشخصي الذي تريد التفعيل عليه.',
                      'تأكد أن الحساب الظاهر في الأعلى هو الحساب الصحيح.',
                      'الصق رابط التفعيل في شريط العنوان واضغط Enter.',
                      'اتبع تعليمات Google لإتمام التفعيل.',
                    ].map((step, i) => (
                      <li key={i} className="flex items-start gap-2.5 text-sm text-foreground">
                        <span className="flex-shrink-0 w-5 h-5 rounded-full bg-primary/10 text-primary text-xs font-bold flex items-center justify-center mt-0.5">
                          {i + 1}
                        </span>
                        {step}
                      </li>
                    ))}
                  </ol>
                  <div className="mt-3 p-3 rounded-lg bg-amber-50 border border-amber-200 space-y-1">
                    <p className="text-xs font-semibold text-amber-800">
                      ⚠️ تحذير: تأكد من الحساب قبل التفعيل، وبعد تأكيد التفعيل وربط الاشتراك بالحساب قد لا يمكن التراجع عن العملية.
                    </p>
                  </div>
                </CardContent>
              )}
            </Card>
          </>
        )}
      </div>
    </CustomerLayout>
  );
}
