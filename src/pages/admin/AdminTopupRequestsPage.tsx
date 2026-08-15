import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, CheckCircle2, XCircle, ArrowLeft, RefreshCw,
  Phone, Zap, User, Hash, Filter, Smartphone, ScanLine,
  AlertTriangle, Clock, ShieldAlert,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { WalletTopupRequest, Profile } from '@/types/types';
import { toast } from 'sonner';

const STATUS_LABELS: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  pending:       { label: 'قيد المراجعة',   variant: 'secondary' },
  scanning:      { label: 'جاري الفحص',     variant: 'secondary' },
  approved:      { label: 'تمت الموافقة',   variant: 'default' },
  rejected:      { label: 'مرفوض',          variant: 'destructive' },
};

const SCAN_STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  pending:       { label: 'قيد الانتظار',   color: 'text-muted-foreground' },
  scanning:      { label: 'جاري الفحص',     color: 'text-blue-500' },
  verified:      { label: 'تم التحقق',      color: 'text-green-500' },
  approved:      { label: 'موافَق عليه',    color: 'text-green-600' },
  rejected:      { label: 'مرفوض',          color: 'text-destructive' },
  manual_review: { label: 'مراجعة يدوية',  color: 'text-amber-500' },
  not_found:     { label: 'لم يوجد',        color: 'text-muted-foreground' },
  amount_mismatch: { label: 'مبلغ غير مطابق', color: 'text-orange-500' },
  duplicate:     { label: 'مكرر',           color: 'text-purple-500' },
};

type FilterStatus = 'all' | 'pending' | 'scanning' | 'approved' | 'rejected';

export default function AdminTopupRequestsPage() {
  const navigate = useNavigate();
  const [requests, setRequests] = useState<WalletTopupRequest[]>([]);
  const [customers, setCustomers] = useState<Record<string, Profile>>({});
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState<string | null>(null);
  const [filter, setFilter] = useState<FilterStatus>('all');
  const channelRef = useRef<ReturnType<typeof supabase.channel> | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    let query = supabase
      .from('wallet_topup_requests')
      .select('*, profiles!customer_id(id, email, phone, wallet_balance)')
      .order('created_at', { ascending: false })
      .limit(100);
    if (filter !== 'all') query = query.eq('status', filter);
    const { data } = await query;
    const rows = (data ?? []) as unknown as WalletTopupRequest[];
    setRequests(rows);
    const custMap: Record<string, Profile> = {};
    (data ?? []).forEach((row: any) => {
      if (row.profiles) custMap[row.profiles.id] = row.profiles as Profile;
    });
    setCustomers(custMap);
    setLoading(false);
  }, [filter]);

  // Realtime subscription
  useEffect(() => {
    load();
    const channelName = `admin-topup-realtime-${crypto.randomUUID()}`;
    const channel = supabase
      .channel(channelName)
      .on('postgres_changes', { event: '*', schema: 'public', table: 'wallet_topup_requests' }, (payload) => {
        if (payload.eventType === 'INSERT') {
          const newRow = payload.new as WalletTopupRequest;
          if (filter === 'all' || newRow.status === filter) {
            setRequests((prev) => [newRow, ...prev]);
            toast.info('طلب شحن جديد وصل!');
          }
        } else if (payload.eventType === 'UPDATE') {
          const updated = payload.new as WalletTopupRequest;
          setRequests((prev) => prev.map((r) => r.id === updated.id ? { ...r, ...updated } : r));
          if (updated.status === 'approved') toast.success('تم تأكيد طلب شحن تلقائياً ✓');
          if ((updated as any).scan_status === 'duplicate') toast.warning('تم رفض عملية مكررة');
        }
      })
      .subscribe();
    channelRef.current = channel;
    return () => { supabase.removeChannel(channel).catch(() => {}); };
  }, [load, filter]);

  const handleApprove = async (r: WalletTopupRequest) => {
    if (!confirm(`تأكيد إضافة ${r.amount} Credit للعميل؟`)) return;
    setProcessing(r.id);
    try {
      const { error } = await supabase.functions.invoke('admin-wallet-topup', {
        body: {
          customer_id: r.customer_id,
          type: 'credit',
          amount: r.amount,
          reason: `شحن فودافون كاش يدوي - ${(r as any).sender_phone ?? ''}`,
        },
      });
      if (error) { toast.error('فشل إضافة الرصيد: ' + error.message); return; }
      await supabase.from('wallet_topup_requests').update({
        status: 'approved',
        processed_at: new Date().toISOString(),
        matched_automatically: false,
        notes: 'موافقة يدوية من الأدمن',
      }).eq('id', r.id);
      toast.success(`✓ تمت الموافقة على شحن ${r.amount} Credit`);
    } finally {
      setProcessing(null);
    }
  };

  const handleReject = async (r: WalletTopupRequest) => {
    if (!confirm('هل تريد رفض هذا الطلب؟')) return;
    setProcessing(r.id);
    await supabase.from('wallet_topup_requests').update({
      status: 'rejected',
      processed_at: new Date().toISOString(),
      notes: 'مرفوض من الأدمن',
    }).eq('id', r.id);
    toast.info('تم رفض الطلب');
    setProcessing(null);
  };

  const stats = {
    total: requests.length,
    pending: requests.filter((r) => r.status === 'pending').length,
    scanning: requests.filter((r) => r.status === 'scanning').length,
    approved: requests.filter((r) => r.status === 'approved').length,
    duplicate: requests.filter((r) => (r as any).scan_status === 'duplicate').length,
    amountMismatch: requests.filter((r) => (r as any).scan_status === 'amount_mismatch').length,
  };

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6">
        {/* Header */}
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/admin/wallet')}>
            <ArrowLeft className="w-4 h-4" />
          </Button>
          <div className="space-y-0.5 flex-1 min-w-0">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2 flex-wrap">
              <Wallet className="w-5 h-5 text-primary" />
              طلبات شحن الرصيد
              {stats.pending > 0 && <Badge variant="destructive" className="text-xs">{stats.pending} معلق</Badge>}
              {stats.scanning > 0 && <Badge variant="secondary" className="text-xs gap-1"><ScanLine className="w-3 h-3" /> {stats.scanning} قيد الفحص</Badge>}
              {stats.duplicate > 0 && <Badge className="text-xs gap-1 bg-purple-500/10 text-purple-600"><ShieldAlert className="w-3 h-3" /> {stats.duplicate} مكرر</Badge>}
              {stats.amountMismatch > 0 && <Badge className="text-xs gap-1 bg-orange-500/10 text-orange-600"><AlertTriangle className="w-3 h-3" /> {stats.amountMismatch} غير مطابق</Badge>}
            </h1>
            <p className="text-sm text-muted-foreground">يتحدث تلقائياً — بدون refresh</p>
          </div>
          <Button variant="outline" size="sm" className="gap-1 shrink-0" onClick={load} disabled={loading}>
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} /> تحديث
          </Button>
        </div>

        {/* Stats row */}
        <div className="grid grid-cols-3 md:grid-cols-6 gap-2">
          {[
            { label: 'الكل', value: stats.total, color: 'text-foreground' },
            { label: 'معلق', value: stats.pending, color: 'text-amber-500' },
            { label: 'فحص', value: stats.scanning, color: 'text-blue-500' },
            { label: 'موافق', value: stats.approved, color: 'text-green-500' },
            { label: 'مكرر', value: stats.duplicate, color: 'text-purple-500' },
            { label: 'غير مطابق', value: stats.amountMismatch, color: 'text-orange-500' },
          ].map(({ label, value, color }) => (
            <Card key={label} className="text-center p-2">
              <p className={`text-xl font-bold ${color}`}>{value}</p>
              <p className="text-[11px] text-muted-foreground">{label}</p>
            </Card>
          ))}
        </div>

        {/* Filter */}
        <div className="flex items-center gap-2">
          <Filter className="w-4 h-4 text-muted-foreground shrink-0" />
          <Select value={filter} onValueChange={(v) => setFilter(v as FilterStatus)}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">جميع الطلبات</SelectItem>
              <SelectItem value="pending">قيد المراجعة</SelectItem>
              <SelectItem value="scanning">جاري الفحص</SelectItem>
              <SelectItem value="approved">تمت الموافقة</SelectItem>
              <SelectItem value="rejected">مرفوض</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Requests list */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">الطلبات ({requests.length})</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-8"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : requests.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">لا توجد طلبات</p>
            ) : (
              <div className="divide-y divide-border">
                {requests.map((r) => {
                  const c = customers[r.customer_id];
                  const fp = r.fingerprint_amount;
                  const cr = r.credits_requested;
                  const txId = r.transaction_id;
                  const auto = r.matched_automatically;
                  const failReason = r.failure_reason;
                  const scanStatus = (r as any).scan_status as string | undefined;
                  const scanCfg = scanStatus ? SCAN_STATUS_CONFIG[scanStatus] : null;
                  const isDuplicate = scanStatus === 'duplicate';
                  const isAmountMismatch = scanStatus === 'amount_mismatch';
                  const statusCfg = STATUS_LABELS[r.status] ?? { label: r.status, variant: 'outline' as const };

                  return (
                    <div key={r.id} className={`p-4 space-y-3 ${isDuplicate ? 'bg-purple-500/5' : isAmountMismatch ? 'bg-orange-500/5' : ''}`}>
                      {/* Top row */}
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <p className="text-sm font-medium text-foreground truncate">{c?.email ?? r.customer_id.slice(0, 12)}</p>
                            {r.order_number && (
                              <Badge variant="outline" className="text-[10px] font-mono gap-1 shrink-0">
                                <Hash className="w-3 h-3" />#{r.order_number}
                              </Badge>
                            )}
                          </div>
                          <p className="text-xs text-muted-foreground font-mono">{r.id.slice(0, 12)}…</p>
                          <p className="text-xs text-muted-foreground flex items-center gap-1 mt-0.5">
                            <Clock className="w-3 h-3" />
                            {new Date(r.created_at).toLocaleString('ar-EG')}
                          </p>
                        </div>
                        <div className="flex flex-col items-end gap-1 shrink-0">
                          <Badge variant={statusCfg.variant}>{statusCfg.label}</Badge>
                          {auto && <span className="text-xs text-green-500 flex items-center gap-1"><Zap className="w-3 h-3" /> تلقائي</span>}
                          {scanCfg && scanStatus !== r.status && (
                            <span className={`text-xs ${scanCfg.color}`}>{scanCfg.label}</span>
                          )}
                          {isDuplicate && <Badge className="text-[10px] bg-purple-500/10 text-purple-600 gap-1"><ShieldAlert className="w-3 h-3" /> مكرر</Badge>}
                        </div>
                      </div>

                      {/* Amount row */}
                      <div className="grid grid-cols-2 gap-2">
                        <div className="p-2.5 rounded-lg bg-muted/20 border border-border">
                          <p className="text-xs text-muted-foreground mb-1">الكريدات</p>
                          <p className="text-base font-bold text-primary">{cr ?? r.amount} Credit</p>
                        </div>
                        <div className="p-2.5 rounded-lg bg-amber-500/5 border border-amber-500/20">
                          <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                            <Zap className="w-3 h-3 text-amber-500" /> المبلغ المتوقع
                          </p>
                          <p className={`text-base font-bold font-mono ${isAmountMismatch ? 'text-orange-500' : 'text-amber-500'}`} dir="ltr">
                            {fp ? fp.toFixed(2) : r.amount.toFixed(2)} ج
                          </p>
                        </div>
                      </div>

                      {/* Device info */}
                      {r.assigned_device_id && (
                        <div className="p-2.5 rounded-lg bg-primary/5 border border-primary/10 text-xs">
                          <div className="flex items-center gap-1.5 text-muted-foreground">
                            <Smartphone className="w-3.5 h-3.5 shrink-0 text-primary" />
                            <span>جهاز الفحص: <span className="font-mono">{r.assigned_device_id.slice(0, 14)}…</span></span>
                          </div>
                          {r.scanning_started_at && (
                            <p className="text-muted-foreground mt-1">
                              بدأ: {new Date(r.scanning_started_at).toLocaleString('ar-EG')}
                            </p>
                          )}
                        </div>
                      )}

                      {/* SMS data */}
                      {(r.sender_phone || r.sender_name || txId) && (
                        <div className="p-2.5 rounded-lg bg-muted/10 border border-border space-y-1.5 text-xs">
                          <p className="text-muted-foreground font-medium">بيانات SMS</p>
                          {r.sender_phone && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <Phone className="w-3.5 h-3.5 shrink-0" />
                              <span dir="ltr">{r.sender_phone}</span>
                            </p>
                          )}
                          {r.sender_name && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <User className="w-3.5 h-3.5 shrink-0" />
                              {r.sender_name}
                            </p>
                          )}
                          {txId && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <Hash className="w-3.5 h-3.5 shrink-0" />
                              <span className="font-mono" dir="ltr">{txId}</span>
                              {isDuplicate && <span className="text-purple-500 font-medium">(مستخدم سابقاً)</span>}
                            </p>
                          )}
                        </div>
                      )}

                      {/* Failure reason */}
                      {failReason && (
                        <p className={`text-xs px-2.5 py-1.5 rounded-md border flex items-start gap-1.5 ${
                          isDuplicate
                            ? 'text-purple-600 bg-purple-500/5 border-purple-500/20'
                            : isAmountMismatch
                            ? 'text-orange-600 bg-orange-500/5 border-orange-500/20'
                            : 'text-amber-600 bg-amber-500/5 border-amber-500/20'
                        }`}>
                          <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
                          {failReason}
                        </p>
                      )}

                      {/* Manual actions — only for truly pending/manual_review cases */}
                      {(r.status === 'pending' || scanStatus === 'manual_review' || scanStatus === 'amount_mismatch' || scanStatus === 'not_found') &&
                        !isDuplicate && r.status !== 'approved' && r.status !== 'rejected' && (
                        <div className="flex items-center gap-2">
                          <Button size="sm" className="flex-1 gap-1.5" onClick={() => handleApprove(r)} disabled={processing === r.id}>
                            {processing === r.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                            موافقة يدوية
                          </Button>
                          <Button size="sm" variant="outline" className="flex-1 gap-1.5" onClick={() => handleReject(r)} disabled={processing === r.id}>
                            <XCircle className="w-3.5 h-3.5" />
                            رفض
                          </Button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  );
}
