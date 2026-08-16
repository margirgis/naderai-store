import React, { useState } from 'react';
import { Wallet, RefreshCw, Clock } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { toast } from 'sonner';
import { refreshBalance } from '@/lib/api';
import type { BalanceResult } from '@/types/types';
import { cn } from '@/lib/utils';

interface Props {
  onBalanceRefreshed?: () => void;
}

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2 border-b border-border last:border-0">
      <span className="text-xs text-muted-foreground uppercase tracking-wide shrink-0">{label}</span>
      <span className="text-sm font-medium text-foreground text-right truncate">{value}</span>
    </div>
  );
}

export function ProviderBalanceCard({ onBalanceRefreshed }: Props) {
  const [loading, setLoading] = useState(false);
  const [balance, setBalance] = useState<BalanceResult | null>(null);

  const handleRefresh = async () => {
    setLoading(true);
    try {
      const res = await refreshBalance();
      if (res.success) {
        setBalance(res);
        toast.success('تم تحديث الرصيد');
        onBalanceRefreshed?.();
      } else {
        toast.error(res.error ?? 'فشل جلب الرصيد');
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'فشل جلب الرصيد');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="bg-card border-border">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Wallet className="w-4 h-4 text-primary shrink-0" />
            <CardTitle className="text-sm font-semibold">رصيد المزود</CardTitle>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 gap-1.5 text-xs shrink-0"
            onClick={handleRefresh}
            disabled={loading}
          >
            <RefreshCw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
            تحديث
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-0">
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4].map(i => <Skeleton key={i} className="h-8 w-full bg-muted" />)}
          </div>
        ) : balance ? (
          <>
            <InfoRow
              label="الرصيد الحالي"
              value={
                <span className="text-primary font-semibold">
                  {balance.credit != null ? balance.credit.toLocaleString() : '—'}
                </span>
              }
            />
            {balance.credit_equivalent != null && (
              <InfoRow label="المعادل" value={balance.credit_equivalent.toLocaleString()} />
            )}
            {balance.total_topup != null && (
              <InfoRow label="إجمالي الشحن" value={balance.total_topup.toLocaleString()} />
            )}
            <InfoRow label="العملة" value={balance.currency ?? '—'} />
            <InfoRow
              label="آخر مزامنة"
              value={
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Clock className="w-3 h-3 shrink-0" />
                  {new Date(balance.synced_at).toLocaleString()}
                </span>
              }
            />
          </>
        ) : (
          <div className="py-6 text-center">
            <p className="text-sm text-muted-foreground">لا توجد بيانات رصيد بعد.</p>
            <p className="text-xs text-muted-foreground mt-1">اضغط "تحديث" لجلب البيانات من المزود.</p>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
