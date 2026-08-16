import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  ArrowRight, Loader2, AlertCircle, ScrollText, CheckCircle2, XCircle,
  ExternalLink, Copy, Check, RefreshCw,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { OrderStatusBadge } from '@/components/customer/OrderStatusBadge';
import type { Order } from '@/types/types';
import { toast } from 'sonner';

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between py-2.5 border-b border-border/50 last:border-0 gap-4">
      <span className="text-xs text-muted-foreground shrink-0 min-w-28">{label}</span>
      <span className="text-sm text-foreground text-end min-w-0 break-all">{value}</span>
    </div>
  );
}

function CopyBtn({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button onClick={async () => {
      try { await navigator.clipboard.writeText(text); } catch { /* fallback */ }
      setCopied(true); setTimeout(() => setCopied(false), 2000);
    }} className="flex items-center gap-1 text-xs text-primary hover:underline shrink-0">
      {copied ? <><Check className="w-3 h-3 text-green-600" />تم النسخ</> : <><Copy className="w-3 h-3" />نسخ</>}
    </button>
  );
}

export default function AdminOrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const navigate = useNavigate();
  const [order, setOrder] = useState<Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [polling, setPolling] = useState(false);

  const load = useCallback(async () => {
    if (!orderId) return;
    const { data } = await supabase
      .from('orders')
      .select('*, provider_services!service_id(name, display_name_ar, input_type, provider_code), profiles!customer_id(email)')
      .eq('id', orderId)
      .maybeSingle();
    setOrder(data as unknown as Order ?? null);
    setLoading(false);
  }, [orderId]);

  useEffect(() => { load(); }, [load]);

  // Realtime live updates
  useEffect(() => {
    if (!orderId) return;
    const ch = supabase.channel(`admin-order:${orderId}`)
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'orders', filter: `id=eq.${orderId}` },
        (payload) => { setOrder(prev => prev ? { ...prev, ...payload.new } : prev); })
      .subscribe();
    return () => { supabase.removeChannel(ch); };
  }, [orderId]);

  const handlePollNow = async () => {
    if (!orderId) return;
    setPolling(true);
    try {
      const { data } = await supabase.functions.invoke('get-order-status', { body: { order_id: orderId } });
      if (data?.status_changed) toast.success('تحديثت حالة الطلب');
      else toast.info('لا يوجد تحديث جديد من المزود');
      await load();
    } catch { toast.error('فشل الاستعلام'); }
    finally { setPolling(false); }
  };

  if (loading) return (
    <AdminLayout>
      <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
    </AdminLayout>
  );
  if (!order) return (
    <AdminLayout>
      <div className="flex flex-col items-center py-16 gap-2">
        <AlertCircle className="w-8 h-8 text-destructive" />
        <p className="text-sm text-muted-foreground">الطلب غير موجود</p>
      </div>
    </AdminLayout>
  );

  const result = order.result_data as Record<string, string> | null;
  const isTerminal = ['success', 'partial', 'failed', 'cancelled', 'rejected'].includes(order.status);
  const offerLink = order.offer_link ?? (result?.offer_link as string) ?? (result?.two_fa_link as string) ?? null;
  const svc = order.provider_services;

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 max-w-2xl mx-auto space-y-5">
        <button onClick={() => navigate('/admin/orders')}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ArrowRight className="w-4 h-4" />
          العودة للطلبات
        </button>

        <div className="flex items-start justify-between gap-3 flex-wrap">
          <div className="space-y-0.5 min-w-0">
            <h1 className="text-lg font-bold text-foreground">تفاصيل الطلب (Admin)</h1>
            <p className="text-xs font-mono text-muted-foreground">{order.reference}</p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <OrderStatusBadge status={order.status} />
            {!isTerminal && (
              <Button size="sm" variant="outline" className="gap-1.5 text-xs" onClick={handlePollNow} disabled={polling}>
                {polling ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
                استعلام الآن
              </Button>
            )}
          </div>
        </div>

        {/* Core info */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-2"><CardTitle className="text-sm">معلومات الطلب</CardTitle></CardHeader>
          <CardContent className="space-y-0 p-4">
            <InfoRow label="العميل" value={order.profiles?.email ?? order.customer_id} />
            <InfoRow label="الخدمة" value={svc?.display_name_ar ?? svc?.name ?? order.provider_service_code} />
            <InfoRow label="كود الخدمة (Provider)" value={<span className="font-mono text-xs">{order.provider_service_code}</span>} />
            <InfoRow label="الكمية" value={order.quantity ?? '—'} />
            <InfoRow label="سعر العميل" value={<span className="text-primary font-semibold">{order.customer_total?.toLocaleString('ar-SA')} ك</span>} />
            <InfoRow label="تكلفة المزود" value={`${order.provider_cost?.toLocaleString('ar-SA')} ك`} />
            <InfoRow label="Task ID (Provider)" value={
              <div className="flex items-center gap-2">
                <span className="font-mono text-xs break-all">{order.provider_task_id ?? '—'}</span>
                {order.provider_task_id && <CopyBtn text={order.provider_task_id} />}
              </div>
            } />
            <InfoRow label="Request ID" value={<span className="font-mono text-xs">{order.provider_request_id ?? '—'}</span>} />
            <InfoRow label="عدد الاستعلامات" value={order.poll_count ?? 0} />
            <InfoRow label="آخر استعلام" value={order.last_polled_at ? new Date(order.last_polled_at).toLocaleString('ar-SA') : '—'} />
            <InfoRow label="نتيجة متاحة" value={order.result_available
              ? <span className="text-green-600 flex items-center gap-1"><CheckCircle2 className="w-3.5 h-3.5" />نعم</span>
              : <span className="text-muted-foreground flex items-center gap-1"><XCircle className="w-3.5 h-3.5" />لا</span>} />
            <InfoRow label="تاريخ الإنشاء" value={new Date(order.created_at).toLocaleString('ar-SA')} />
            {order.completed_at && <InfoRow label="تاريخ الإكمال" value={new Date(order.completed_at).toLocaleString('ar-SA')} />}
            {order.webhook_received_at && (
              <InfoRow label="Webhook وصل" value={new Date(order.webhook_received_at).toLocaleString('ar-SA')} />
            )}
          </CardContent>
        </Card>

        {/* Offer link (admin view) */}
        {offerLink && (
          <Card className="bg-card border-green-200">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-green-700 flex items-center gap-2">🎁 رابط التفعيل</CardTitle>
            </CardHeader>
            <CardContent className="p-4 space-y-2">
              <div className="p-3 bg-green-50 rounded-lg border border-green-100 break-all text-xs font-mono text-green-900">
                {offerLink}
              </div>
              <div className="flex items-center gap-3">
                <CopyBtn text={offerLink} />
                <a href={offerLink} target="_blank" rel="noopener noreferrer"
                  className="flex items-center gap-1 text-xs text-primary hover:underline">
                  <ExternalLink className="w-3 h-3" />فتح
                </a>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Error info */}
        {(order.safe_error_code || order.safe_error_message) && (
          <Card className="bg-destructive/5 border-destructive/20">
            <CardHeader className="pb-2"><CardTitle className="text-sm text-destructive">خطأ الطلب</CardTitle></CardHeader>
            <CardContent className="space-y-0 p-4">
              {order.safe_error_code && <InfoRow label="كود الخطأ" value={<span className="font-mono text-xs">{order.safe_error_code}</span>} />}
              {order.safe_error_message && <InfoRow label="رسالة الخطأ" value={order.safe_error_message} />}
            </CardContent>
          </Card>
        )}

        {/* Safe result data */}
        {result && Object.keys(result).length > 0 && (
          <Card className="bg-card border-border">
            <CardHeader className="pb-2"><CardTitle className="text-sm flex items-center gap-2">
              <ScrollText className="w-4 h-4 text-muted-foreground" />بيانات النتيجة
            </CardTitle></CardHeader>
            <CardContent className="space-y-0 p-4">
              {Object.entries(result).map(([k, v]) => (
                <InfoRow key={k} label={k} value={<span className="font-mono text-xs break-all">{String(v)}</span>} />
              ))}
            </CardContent>
          </Card>
        )}
      </div>
    </AdminLayout>
  );
}
