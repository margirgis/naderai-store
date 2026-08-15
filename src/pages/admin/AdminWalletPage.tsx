import React, { useCallback, useEffect, useState } from 'react';
import { Wallet, Search, Loader2, Plus, Minus } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { Profile, WalletTransaction } from '@/types/types';
import { toast } from 'sonner';

const PAGE_SIZE = 15;

export default function AdminWalletPage() {
  const [customers, setCustomers] = useState<Profile[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);

  // Topup dialog
  const [selected, setSelected] = useState<Profile | null>(null);
  const [txType, setTxType] = useState<'credit' | 'debit'>('credit');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [txLoading, setTxLoading] = useState(false);
  const [open, setOpen] = useState(false);

  // Tx history
  const [txs, setTxs] = useState<WalletTransaction[]>([]);
  const [txLoaded, setTxLoaded] = useState(false);
  const [txPage, setTxPage] = useState(1);
  const TX_PAGE_SIZE = 30;

  const loadCustomers = useCallback(async (p: number, q: string) => {
    setLoading(true);
    let query = supabase.from('profiles').select('*').eq('role', 'user')
      .order('created_at', { ascending: false })
      .range((p - 1) * PAGE_SIZE, p * PAGE_SIZE - 1);
    if (q.trim()) query = query.ilike('email', `%${q.trim()}%`);
    const { data } = await query;
    const rows = (data ?? []) as Profile[];
    setCustomers(rows);
    setHasMore(rows.length === PAGE_SIZE);
    setLoading(false);
  }, []);

  const loadTxs = useCallback(async () => {
    const { data } = await supabase
      .from('wallet_transactions')
      .select('*, profiles!customer_id(email)')
      .order('created_at', { ascending: false })
      .range((txPage - 1) * TX_PAGE_SIZE, txPage * TX_PAGE_SIZE - 1);
    setTxs((data ?? []) as unknown as WalletTransaction[]);
    setTxLoaded(true);
  }, [txPage]);

  useEffect(() => { loadCustomers(page, search); }, [page, search, loadCustomers]);
  useEffect(() => { loadTxs(); }, [loadTxs, txPage]);

  const handleTopup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected || !amount || !reason) return;
    const numAmount = parseFloat(amount);
    if (isNaN(numAmount) || numAmount <= 0) { toast.error('مبلغ غير صحيح'); return; }
    setTxLoading(true);
    try {
      const { data, error } = await supabase.functions.invoke('admin-wallet-topup', {
        body: { customer_id: selected.id, type: txType, amount: numAmount, reason },
      });
      if (error) {
        const msg = await error?.context?.text?.() ?? error.message;
        toast.error(msg || 'فشلت العملية');
        return;
      }
      toast.success(txType === 'credit' ? `تمت إضافة ${numAmount} ك للعميل` : `تم خصم ${numAmount} ك`);
      setOpen(false);
      setAmount('');
      setReason('');
      setSelected(null);
      await Promise.all([loadCustomers(page, search), loadTxs()]);
    } finally {
      setTxLoading(false);
    }
  };

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6">
        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <Wallet className="w-5 h-5 text-primary" /> إدارة المحافظ
          </h1>
          <p className="text-sm text-muted-foreground">إضافة وخصم رصيد العملاء للاختبار</p>
        </div>

        {/* Customer list */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3 flex flex-row items-center justify-between">
            <CardTitle className="text-sm">العملاء</CardTitle>
            <div className="relative">
              <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
              <Input placeholder="ابحث…" value={search}
                onChange={e => { setSearch(e.target.value); setPage(1); }}
                className="pe-10 bg-background border-border w-48 h-8 text-sm" />
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-8"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : customers.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">لا يوجد عملاء</p>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">البريد الإلكتروني</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الرصيد</th>
                      <th className="py-3 px-4"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {customers.map(c => (
                      <tr key={c.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                        <td className="py-2.5 px-4 text-foreground whitespace-nowrap">{c.email ?? '—'}</td>
                        <td className="py-2.5 px-4 text-end font-semibold text-primary whitespace-nowrap">
                          {(c.wallet_balance ?? 0).toFixed(1)} Credit
                        </td>
                        <td className="py-2.5 px-4 whitespace-nowrap">
                          <Dialog open={open && selected?.id === c.id}
                            onOpenChange={v => { setOpen(v); if (!v) setSelected(null); }}>
                            <DialogTrigger asChild>
                              <Button size="sm" variant="secondary" className="h-7 text-xs gap-1"
                                onClick={() => { setSelected(c); setTxType('credit'); setAmount(''); setReason(''); }}>
                                <Wallet className="w-3 h-3" /> إدارة الرصيد
                              </Button>
                            </DialogTrigger>
                            <DialogContent className="max-w-[calc(100%-2rem)] md:max-w-sm">
                              <DialogHeader>
                                <DialogTitle className="text-sm">إدارة رصيد العميل</DialogTitle>
                              </DialogHeader>
                              <p className="text-xs text-muted-foreground">{c.email}</p>
                              <p className="text-xs">الرصيد الحالي: <span className="text-primary font-semibold">{(c.wallet_balance ?? 0).toFixed(1)} Credit</span></p>
                              <form onSubmit={handleTopup} className="space-y-4 mt-2">
                                {/* Type toggle */}
                                <div className="flex gap-2">
                                  <Button type="button" size="sm"
                                    variant={txType === 'credit' ? 'default' : 'secondary'}
                                    className="flex-1 gap-1.5" onClick={() => setTxType('credit')}>
                                    <Plus className="w-3.5 h-3.5" /> إضافة
                                  </Button>
                                  <Button type="button" size="sm"
                                    variant={txType === 'debit' ? 'default' : 'secondary'}
                                    className="flex-1 gap-1.5" onClick={() => setTxType('debit')}>
                                    <Minus className="w-3.5 h-3.5" /> خصم
                                  </Button>
                                </div>
                                <div className="space-y-1.5">
                                  <Label className="text-xs text-muted-foreground">المبلغ (كريديت)</Label>
                                  <Input type="number" min="0.01" step="0.01" placeholder="100"
                                    value={amount} onChange={e => setAmount(e.target.value)}
                                    className="bg-background border-border" required />
                                </div>
                                <div className="space-y-1.5">
                                  <Label className="text-xs text-muted-foreground">السبب</Label>
                                  <Input placeholder="رصيد اختبار — من المسؤول" value={reason}
                                    onChange={e => setReason(e.target.value)}
                                    className="bg-background border-border" required />
                                </div>
                                <Button type="submit" className="w-full" disabled={txLoading}>
                                  {txLoading && <Loader2 className="w-4 h-4 ms-2 animate-spin" />}
                                  {txLoading ? 'جارٍ التنفيذ…' : (txType === 'credit' ? 'إضافة الرصيد' : 'خصم الرصيد')}
                                </Button>
                              </form>
                            </DialogContent>
                          </Dialog>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {!loading && (page > 1 || hasMore) && (
          <div className="flex items-center justify-between">
            <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>السابق</Button>
            <span className="text-xs text-muted-foreground">صفحة {page}</span>
            <Button variant="ghost" size="sm" disabled={!hasMore} onClick={() => setPage(p => p + 1)}>التالي</Button>
          </div>
        )}

        {/* Recent transactions */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3 flex flex-row items-center justify-between">
            <CardTitle className="text-sm">آخر المعاملات</CardTitle>
            <div className="flex gap-1">
              <Button variant="ghost" size="sm" disabled={txPage <= 1} onClick={() => setTxPage(p => p - 1)}>السابق</Button>
              <span className="text-xs text-muted-foreground self-center px-1">#{txPage}</span>
              <Button variant="ghost" size="sm" disabled={txs.length < TX_PAGE_SIZE} onClick={() => setTxPage(p => p + 1)}>التالي</Button>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {!txLoaded ? (
              <div className="flex justify-center py-6"><Loader2 className="w-4 h-4 animate-spin text-muted-foreground" /></div>
            ) : txs.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-6">لا توجد معاملات</p>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">النوع</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">المبلغ</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">قبل</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">بعد</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">العميل</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">السبب</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">التاريخ</th>
                    </tr>
                  </thead>
                  <tbody>
                    {txs.map(tx => {
                      const balBefore = (tx as any).balance_before as number | null;
                      const balAfter = (tx as any).balance_after as number | null;
                      const custEmail = (tx as any).profiles?.email as string | null;
                      return (
                        <tr key={tx.id} className="border-b border-border/50 hover:bg-muted/20">
                          <td className="py-2.5 px-4 whitespace-nowrap">
                            <span className={`text-xs font-semibold ${tx.type === 'credit' ? 'text-green-500' : 'text-destructive'}`}>
                              {tx.type === 'credit' ? '+ إضافة' : '- خصم'}
                            </span>
                          </td>
                          <td className="py-2.5 px-4 text-end font-mono text-xs whitespace-nowrap font-semibold">
                            {tx.amount?.toFixed(1)}
                          </td>
                          <td className="py-2.5 px-4 text-end font-mono text-xs whitespace-nowrap text-muted-foreground">
                            {balBefore != null ? balBefore.toFixed(1) : '—'}
                          </td>
                          <td className="py-2.5 px-4 text-end font-mono text-xs whitespace-nowrap text-primary font-semibold">
                            {balAfter != null ? balAfter.toFixed(1) : '—'}
                          </td>
                          <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap max-w-32 truncate">
                            {custEmail ?? tx.customer_id?.slice(0, 8) ?? '—'}
                          </td>
                          <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap max-w-48 truncate">
                            {tx.reason}
                          </td>
                          <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap">
                            {new Date(tx.created_at).toLocaleString('ar-EG', { dateStyle: 'short', timeStyle: 'short' })}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  );
}
