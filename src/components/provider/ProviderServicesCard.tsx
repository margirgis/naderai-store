import React, { useState, useEffect, useCallback } from 'react';
import { RefreshCw, Package, AlertCircle, CheckCircle2, Clock, ChevronLeft, ChevronRight } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { toast } from 'sonner';
import { syncServices, getProviderServices } from '@/lib/api';
import type { ProviderService } from '@/types/types';
import { cn } from '@/lib/utils';

interface Props {
  stats: { total: number; available: number; maintenance: number };
  onSyncComplete: () => void;
}

const STATUS_LABELS: Record<string, string> = {
  active: 'نشط',
  maintenance: 'صيانة',
  inactive: 'غير نشط',
};

function ServiceStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    active: 'status-active',
    maintenance: 'status-maintenance',
    inactive: 'status-inactive',
  };
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded text-xs font-medium', map[status] ?? 'status-inactive')}>
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

export function ProviderServicesCard({ stats, onSyncComplete }: Props) {
  const [syncing, setSyncing] = useState(false);
  const [loadingList, setLoadingList] = useState(false);
  const [services, setServices] = useState<ProviderService[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const PAGE_SIZE = 10;

  const loadServices = useCallback(async (p: number) => {
    setLoadingList(true);
    try {
      const { data, count } = await getProviderServices(p, PAGE_SIZE);
      setServices(data);
      setTotal(count);
    } finally {
      setLoadingList(false);
    }
  }, []);

  useEffect(() => {
    loadServices(page);
  }, [page, loadServices]);

  const handleSync = async () => {
    setSyncing(true);
    try {
      const res = await syncServices();
      if (res.success) {
        toast.success(`تمت مزامنة ${res.synced} خدمة من المزود`);
        onSyncComplete();
        await loadServices(1);
        setPage(1);
      } else {
        toast.error(res.error ?? 'فشلت المزامنة');
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'فشلت المزامنة');
    } finally {
      setSyncing(false);
    }
  };

  const totalPages = Math.ceil(total / PAGE_SIZE);

  return (
    <Card className="bg-card border-border">
      <CardHeader className="pb-4">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-2 min-w-0">
            <Package className="w-4 h-4 text-primary shrink-0" />
            <CardTitle className="text-sm font-semibold">خدمات المزود</CardTitle>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <Button variant="default" size="sm" className="h-8 gap-1.5 text-xs" onClick={handleSync} disabled={syncing}>
              <RefreshCw className={cn('w-3.5 h-3.5', syncing && 'animate-spin')} />
              {syncing ? 'جارٍ المزامنة…' : 'مزامنة الخدمات'}
            </Button>
          </div>
        </div>

        {/* صف الإحصاءات */}
        <div className="grid grid-cols-3 gap-2 mt-1">
          {[
            { label: 'الإجمالي', value: stats.total, icon: Package, color: 'text-foreground' },
            { label: 'متاح', value: stats.available, icon: CheckCircle2, color: 'text-green-400' },
            { label: 'صيانة', value: stats.maintenance, icon: AlertCircle, color: 'text-yellow-400' },
          ].map(({ label, value, icon: Icon, color }) => (
            <div key={label} className="p-3 rounded border border-border bg-muted/30 text-center">
              <Icon className={cn('w-4 h-4 mx-auto mb-1', color)} />
              <p className="text-lg font-bold text-foreground">{value}</p>
              <p className="text-xs text-muted-foreground">{label}</p>
            </div>
          ))}
        </div>
      </CardHeader>

      <CardContent className="space-y-2">
        {loadingList ? (
          <div className="space-y-2">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full bg-muted" />
            ))}
          </div>
        ) : services.length === 0 ? (
          <div className="py-8 text-center">
            <Package className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
            <p className="text-sm text-muted-foreground">لا توجد خدمات مزامنة بعد.</p>
            <p className="text-xs text-muted-foreground mt-1">اضغط "مزامنة الخدمات" لجلبها من المزود.</p>
          </div>
        ) : (
          <>
            <div className="overflow-x-auto w-full max-w-full">
              <table className="w-full min-w-max text-sm">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الكود</th>
                    <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الاسم</th>
                    <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الحالة</th>
                    <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">نوع المدخل</th>
                    <th className="text-end py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الرصيد</th>
                    <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">آخر مزامنة</th>
                  </tr>
                </thead>
                <tbody>
                  {services.map((svc) => (
                    <tr key={svc.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                      <td className="py-2.5 px-3 font-mono text-xs text-primary whitespace-nowrap">{svc.provider_code}</td>
                      <td className="py-2.5 px-3 text-foreground whitespace-nowrap max-w-48 truncate">{svc.name}</td>
                      <td className="py-2.5 px-3 whitespace-nowrap"><ServiceStatusBadge status={svc.status} /></td>
                      <td className="py-2.5 px-3 text-xs text-muted-foreground whitespace-nowrap">{svc.input_type ?? '—'}</td>
                      <td className="py-2.5 px-3 text-end font-mono text-xs text-foreground whitespace-nowrap">
                        {svc.provider_credit_price != null ? svc.provider_credit_price.toLocaleString() : '—'}
                      </td>
                      <td className="py-2.5 px-3 whitespace-nowrap">
                        {svc.last_synced_at ? (
                          <span className="flex items-center gap-1 text-xs text-muted-foreground">
                            <Clock className="w-3 h-3 shrink-0" />
                            {new Date(svc.last_synced_at).toLocaleDateString('ar-SA')}
                          </span>
                        ) : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* ترقيم الصفحات */}
            {totalPages > 1 && (
              <div className="flex items-center justify-between pt-2">
                <span className="text-xs text-muted-foreground">
                  صفحة {page} من {totalPages} · {total} خدمة
                </span>
                <div className="flex items-center gap-1">
                  <Button variant="ghost" size="icon" className="h-7 w-7" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
                    <ChevronRight className="w-3.5 h-3.5" />
                  </Button>
                  <Button variant="ghost" size="icon" className="h-7 w-7" disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}>
                    <ChevronLeft className="w-3.5 h-3.5" />
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
