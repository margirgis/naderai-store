import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Wallet, Loader2, CheckCircle2, XCircle, ArrowLeft, RefreshCw,
  Phone, Zap, User, Hash, Filter,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { WalletTopupRequest, Profile } from '@/types/types';
import { toast } from 'sonner';

const STATUS_LABELS: Record<string, string> = {
  pending: 'قيد المراجعة',
  approved: 'تمت الموافقة',
  rejected: 'مرفوض',
};

type FilterStatus = 'all' | 'pending' | 'approved' | 'rejected';

export default function AdminTopupRequestsPage() {
  const navigate = useNavigate();
  const [requests, setRequests] = useState<WalletTopupRequest[]>([]);
  const [customers, setCustomers] = useState<Record<string, Profile>>({});
  const [loading, setLoading] = useState(true);
  const [processing, setProcessing] = useState<string | null>(null);
  const [filter, setFilter] = useState<FilterStatus>('all');

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

  useEffect(() => { load(); }, [load]);

  const handleApprove = async (r: WalletTopupRequest) => {
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
      if (error) { toast.error('فشل إضافة الرصيد'); return; }
      await supabase.from('wallet_topup_requests').update({
        status: 'approved',
        processed_at: new Date().toISOString(),
        matched_automatically: false,
      }).eq('id', r.id);
      toast.success(`تمت الموافقة على شحن ${r.amount} Credit`);
      await load();
    } finally {
      setProcessing(null);
    }
  };

  const handleReject = async (r: WalletTopupRequest) => {
    setProcessing(r.id);
    await supabase.from('wallet_topup_requests').update({
      status: 'rejected',
      processed_at: new Date().toISOString(),
      notes: 'Rejected by admin',
    }).eq('id', r.id);
    toast.info('تم رفض الطلب');
    await load();
    setProcessing(null);
  };

  const pending = requests.filter((r) => r.status === 'pending').length;

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="icon" onClick={() => navigate('/admin/wallet')}>
            <ArrowLeft className="w-4 h-4" />
          </Button>
          <div className="space-y-0.5 flex-1 min-w-0">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Wallet className="w-5 h-5 text-primary" />
              طلبات شحن الرصيد
              {pending > 0 && <Badge variant="destructive" className="text-xs">{pending} معلق</Badge>}
            </h1>
            <p className="text-sm text-muted-foreground">مراجعة والموافقة على طلبات العملاء</p>
          </div>
          <Button variant="outline" size="sm" className="gap-1 shrink-0" onClick={load}>
            <RefreshCw className="w-3.5 h-3.5" /> تحديث
          </Button>
        </div>

        <div className="flex items-center gap-2">
          <Filter className="w-4 h-4 text-muted-foreground shrink-0" />
          <Select value={filter} onValueChange={(v) => setFilter(v as FilterStatus)}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">جميع الطلبات</SelectItem>
              <SelectItem value="pending">قيد المراجعة</SelectItem>
              <SelectItem value="approved">تمت الموافقة</SelectItem>
              <SelectItem value="rejected">مرفوض</SelectItem>
            </SelectContent>
          </Select>
        </div>

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
                  const fp = (r as any).fingerprint_amount as number | null;
                  const cr = (r as any).credits_requested as number | null;
                  const senderName = (r as any).sender_name as string | null;
                  const txId = (r as any).transaction_id as string | null;
                  const auto = (r as any).matched_automatically as boolean | null;
                  const failReason = (r as any).failure_reason as string | null;
                  return (
                    <div key={r.id} className="p-4 space-y-3">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-foreground truncate">{c?.email ?? '—'}</p>
                          <p className="text-xs text-muted-foreground">{new Date(r.created_at).toLocaleString('ar-SA')}</p>
                        </div>
                        <div className="flex flex-col items-end gap-1 shrink-0">
                          <Badge variant={r.status === 'approved' ? 'default' : r.status === 'rejected' ? 'destructive' : 'secondary'}>
                            {STATUS_LABELS[r.status] ?? r.status}
                          </Badge>
                          {auto && <span className="text-xs text-green-500 flex items-center gap-1"><Zap className="w-3 h-3" /> تلقائي</span>}
                        </div>
                      </div>

                      <div className="grid grid-cols-2 gap-2">
                        <div className="p-2.5 rounded-lg bg-muted/20 border border-border">
                          <p className="text-xs text-muted-foreground mb-1">الكريدات</p>
                          <p className="text-base font-bold text-primary">{cr ?? r.amount} Credit</p>
                        </div>
                        <div className="p-2.5 rounded-lg bg-amber-500/5 border border-amber-500/20">
                          <p className="text-xs text-muted-foreground mb-1 flex items-center gap-1">
                            <Zap className="w-3 h-3 text-amber-500" /> المبلغ المتوقع
                          </p>
                          <p className="text-base font-bold font-mono text-amber-500" dir="ltr">
                            {fp ? fp.toFixed(2) : r.amount.toFixed(2)} جنيه
                          </p>
                        </div>
                      </div>

                      {(r.sender_phone || senderName || txId) && (
                        <div className="p-2.5 rounded-lg bg-muted/10 border border-border space-y-1.5 text-xs">
                          <p className="text-muted-foreground font-medium">بيانات SMS المستلمة</p>
                          {r.sender_phone && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <Phone className="w-3.5 h-3.5 shrink-0" />
                              <span dir="ltr">{r.sender_phone}</span>
                            </p>
                          )}
                          {senderName && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <User className="w-3.5 h-3.5 shrink-0" />
                              {senderName}
                            </p>
                          )}
                          {txId && (
                            <p className="flex items-center gap-1.5 text-muted-foreground">
                              <Hash className="w-3.5 h-3.5 shrink-0" />
                              <span className="font-mono" dir="ltr">{txId}</span>
                            </p>
                          )}
                        </div>
                      )}

                      {failReason && r.status === 'pending' && (
                        <p className="text-xs text-amber-500 bg-amber-500/5 px-2.5 py-1.5 rounded-md border border-amber-500/20">
                          سبب التأخر: {failReason}
                        </p>
                      )}

                      {r.notes && r.status !== 'pending' && (
                        <p className="text-xs text-muted-foreground truncate">{r.notes}</p>
                      )}

                      {r.status === 'pending' && (
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
