import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Wallet, TrendingUp, TrendingDown, Loader2, PlusCircle, MessageCircle, Copy, Check, Info } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { useAuth } from '@/contexts/AuthContext';
import type { WalletTransaction } from '@/types/types';

const CREDIT_PRICE_EGP = 300; // سعر الكريدت الواحد بالجنيه المصري
const SUPPORT_PHONE = '201222692182'; // رقم واتساب الدعم (مصر +20)

const TX_LABELS: Record<string, string> = {
  credit: 'إضافة رصيد',
  debit:  'خصم رصيد',
  hold:   'حجز مؤقت',
  release:'تحرير حجز',
};

export default function CustomerWalletPage() {
  const { profile } = useAuth();
  const [txs, setTxs] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [credits, setCredits] = useState<string>('1');
  const [copied, setCopied] = useState(false);
  const PAGE_SIZE = 15;

  const creditNum = parseInt(credits, 10);
  const valid = !isNaN(creditNum) && creditNum >= 1;
  const total = valid ? creditNum * CREDIT_PRICE_EGP : 0;

  const userName = profile?.email?.split('@')[0] ?? 'عميل';
  const phone = profile?.phone ?? '';
  const whatsappMessage = encodeURIComponent(
    `السلام عليكم، أنا ${userName}` +
    (phone ? ` (${phone})` : '') +
    `
أريد شراء ${valid ? creditNum : 0} Credit` +
    `الإجمالي: ${total} جنيه مصري`
  );
  const waHref = `https://wa.me/${SUPPORT_PHONE}?text=${whatsappMessage}`;

  const load = useCallback(async (p: number) => {
    setLoading(true);
    const { data } = await supabase
      .from('wallet_transactions')
      .select('*, orders!order_id(reference)')
      .order('created_at', { ascending: false })
      .range((p - 1) * PAGE_SIZE, p * PAGE_SIZE - 1);
    const rows = (data ?? []) as unknown as WalletTransaction[];
    setTxs(rows);
    setHasMore(rows.length === PAGE_SIZE);
    setLoading(false);
  }, []);

  useEffect(() => { load(page); }, [page, load]);

  const copyPhone = async () => {
    try {
      await navigator.clipboard.writeText(SUPPORT_PHONE);
    } catch {
      // fallback silently
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2_000);
  };

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 space-y-5 max-w-2xl mx-auto">
        <div className="space-y-1">
          <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
            <Wallet className="w-5 h-5 text-primary" />
            محفظتي
          </h1>
        </div>

        {/* Balance card */}
        <Card className="bg-card border-border">
          <CardContent className="p-6 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground mb-1">رصيدك الحالي</p>
              <p className="text-3xl font-bold text-primary">
                {(profile?.wallet_balance ?? 0).toFixed(1)}
                <span className="text-sm font-normal text-muted-foreground me-1"> Credit</span>
              </p>
            </div>
            <Wallet className="w-10 h-10 text-primary/20" />
          </CardContent>
        </Card>

        {/* Top-up card */}
        <Card className="bg-card border-border overflow-hidden">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold flex items-center gap-2">
              <PlusCircle className="w-4 h-4 text-primary" />
              شحن رصيد
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between text-sm">
              <span className="text-muted-foreground">سعر الكريدت الواحدة</span>
              <span className="font-semibold text-primary">{CREDIT_PRICE_EGP} جنيه مصري</span>
            </div>

            <div className="space-y-2">
              <label className="text-sm text-muted-foreground">الكريدات المراد شراؤها</label>
              <Input
                type="number"
                min={1}
                value={credits}
                onChange={(e) => {
                  const v = e.target.value;
                  if (v === '' || /^\d+$/.test(v)) setCredits(v);
                }}
                placeholder="مثلاً 5"
                className="text-center text-lg font-semibold"
              />
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">إجمالي الشحن</span>
                <span className="font-bold text-foreground">{total.toLocaleString('ar-SA')} جنيه مصري</span>
              </div>
            </div>

            <div className="p-3 rounded-lg border border-border bg-muted/20 text-xs flex items-start gap-2">
              <Info className="w-4 h-4 text-primary shrink-0 mt-0.5" />
              <p className="text-muted-foreground">
                يمكنك طلب سعر مخفض للكميات الكبيرة من إدارة الموقع. اتصل بالواتساب للتفاصيل.
              </p>
            </div>

            <div className="flex flex-col gap-2">
              <Button asChild variant="default" className="w-full gap-2">
                <Link to="/store/wallet/topup">
                  <PlusCircle className="w-4 h-4" />
                  إرسال طلب شحن رسمي
                </Link>
              </Button>
              <div className="flex items-center gap-2">
                <div className="flex-1 flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2">
                  <span className="text-sm text-muted-foreground" dir="ltr">+{SUPPORT_PHONE}</span>
                  <button
                    onClick={copyPhone}
                    className="ms-auto text-muted-foreground hover:text-foreground"
                    aria-label="نسخ رقم الواتساب"
                  >
                    {copied ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                  </button>
                </div>
                <Button asChild style={{ backgroundColor: '#25D366', color: '#fff' }} className="gap-2 hover:opacity-90">
                  <a href={waHref} target="_blank" rel="noopener noreferrer">
                    <MessageCircle className="w-4 h-4 fill-current" />
                    واتساب
                  </a>
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Transactions */}
        <Card className="bg-card border-border">
          <CardHeader className="pb-3">
            <CardTitle className="text-sm font-semibold">سجل المعاملات</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-8"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : txs.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">لا توجد معاملات بعد</p>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">النوع</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">السبب</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">المبلغ</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الرصيد بعد</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">التاريخ</th>
                    </tr>
                  </thead>
                  <tbody>
                    {txs.map(tx => (
                      <tr key={tx.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                        <td className="py-2.5 px-4 whitespace-nowrap">
                          <span className={`flex items-center gap-1 text-xs font-medium ${
                            tx.type === 'credit' ? 'text-green-400' : 'text-destructive'
                          }`}>
                            {tx.type === 'credit'
                              ? <TrendingUp className="w-3.5 h-3.5" />
                              : <TrendingDown className="w-3.5 h-3.5" />
                            }
                            {TX_LABELS[tx.type] ?? tx.type}
                          </span>
                        </td>
                        <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap max-w-48 truncate">
                          {tx.reason}
                        </td>
                        <td className={`py-2.5 px-4 text-end font-mono text-xs font-semibold whitespace-nowrap ${
                          tx.type === 'credit' ? 'text-green-400' : 'text-destructive'
                        }`}>
                          {tx.type === 'credit' ? '+' : '-'}{tx.amount?.toFixed(1)} Credit
                        </td>
                        <td className="py-2.5 px-4 text-end font-mono text-xs text-foreground whitespace-nowrap">
                          {tx.balance_after?.toFixed(1)} Credit
                        </td>
                        <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap">
                          {new Date(tx.created_at).toLocaleDateString('ar-SA')}
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
            <button className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-40"
              disabled={page <= 1} onClick={() => setPage(p => p - 1)}>السابق</button>
            <span className="text-xs text-muted-foreground">صفحة {page}</span>
            <button className="text-xs text-muted-foreground hover:text-foreground disabled:opacity-40"
              disabled={!hasMore} onClick={() => setPage(p => p + 1)}>التالي</button>
          </div>
        )}
      </div>
    </CustomerLayout>
  );
}
