import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Loader2, AlertCircle, Package, ChevronRight, ChevronLeft,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { supabase } from '@/db/supabase';
import { OrderStatusBadge } from '@/components/customer/OrderStatusBadge';
import type { Order } from '@/types/types';

const PAGE_SIZE = 15;

export default function MyOrdersPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    const from = page * PAGE_SIZE;
    const to = from + PAGE_SIZE - 1;
    const { data, count } = await supabase
      .from('orders')
      .select('id, reference, status, customer_total, created_at, updated_at, provider_services!service_id(name, display_name_ar)', { count: 'exact' })
      .order('created_at', { ascending: false })
      .range(from, to);
    setOrders((data ?? []) as unknown as Order[]);
    setTotal(count ?? 0);
    setLoading(false);
  }, [page]);

  useEffect(() => { load(); }, [load]);

  // Realtime: update orders list on status change
  useEffect(() => {
    const ch = supabase.channel('my-orders')
      .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'orders' },
        () => { load(); })
      .subscribe();
    return () => { supabase.removeChannel(ch); };
  }, [load]);

  const totalPages = Math.ceil(total / PAGE_SIZE);

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-3xl mx-auto space-y-5">
        <div className="space-y-0.5">
          <h1 className="text-xl font-bold text-foreground">طلباتي</h1>
          <p className="text-sm text-muted-foreground">
            {total > 0 ? `${total} طلب إجمالاً` : 'لا توجد طلبات بعد'}
          </p>
        </div>

        {loading ? (
          <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-muted-foreground" /></div>
        ) : orders.length === 0 ? (
          <div className="text-center py-16 space-y-3">
            <Package className="w-10 h-10 text-muted-foreground mx-auto" />
            <p className="text-sm font-medium text-foreground">لا توجد طلبات بعد</p>
            <Link to="/store/services">
              <Button size="sm">تصفح الخدمات</Button>
            </Link>
          </div>
        ) : (
          <>
            {/* Mobile-first cards — table only on md+ */}
            <div className="hidden md:block">
              <div className="overflow-x-auto rounded-xl border border-border bg-card shadow-sm">
                <table className="w-full">
                  <thead>
                    <tr className="border-b border-border bg-muted/30">
                      <th className="text-right text-xs font-semibold text-muted-foreground px-4 py-3 whitespace-nowrap">Order ID</th>
                      <th className="text-right text-xs font-semibold text-muted-foreground px-4 py-3 whitespace-nowrap">الخدمة</th>
                      <th className="text-right text-xs font-semibold text-muted-foreground px-4 py-3 whitespace-nowrap">الحالة</th>
                      <th className="text-right text-xs font-semibold text-muted-foreground px-4 py-3 whitespace-nowrap">السعر</th>
                      <th className="text-right text-xs font-semibold text-muted-foreground px-4 py-3 whitespace-nowrap">التاريخ</th>
                      <th className="px-4 py-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map(o => {
                      const svc = (o as any).provider_services;
                      const svcName = svc?.display_name_ar ?? svc?.name ?? o.provider_service_code;
                      return (
                        <tr key={o.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                          <td className="px-4 py-3 text-xs font-mono text-muted-foreground whitespace-nowrap">{o.reference}</td>
                          <td className="px-4 py-3 text-sm text-foreground whitespace-nowrap max-w-[140px] truncate">{svcName}</td>
                          <td className="px-4 py-3 whitespace-nowrap"><OrderStatusBadge status={o.status} /></td>
                          <td className="px-4 py-3 text-sm font-semibold text-primary whitespace-nowrap">
                            {(o.customer_total ?? 0).toFixed(1)} Credit
                          </td>
                          <td className="px-4 py-3 text-xs text-muted-foreground whitespace-nowrap">
                            {new Date(o.created_at).toLocaleDateString('en-GB')}
                          </td>
                          <td className="px-4 py-3">
                            <Link to={`/store/orders/${o.id}`} className="text-xs text-primary hover:underline flex items-center gap-1 whitespace-nowrap">
                              تفاصيل <ChevronRight className="w-3 h-3" />
                            </Link>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>

            {/* Mobile cards */}
            <div className="md:hidden space-y-3">
              {orders.map(o => {
                const svc = (o as any).provider_services;
                const svcName = svc?.display_name_ar ?? svc?.name ?? o.provider_service_code;
                return (
                  <Link to={`/store/orders/${o.id}`} key={o.id}
                    className="block bg-card border border-border rounded-xl p-4 space-y-2 shadow-sm hover:border-primary/30 transition-colors">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-foreground truncate">{svcName}</p>
                        <p className="text-xs font-mono text-muted-foreground">{o.reference}</p>
                      </div>
                      <OrderStatusBadge status={o.status} />
                    </div>
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-primary font-bold">{(o.customer_total ?? 0).toFixed(1)} Credit</span>
                      <span className="text-muted-foreground">{new Date(o.created_at).toLocaleDateString('en-GB')}</span>
                    </div>
                  </Link>
                );
              })}
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-center gap-3">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                  <ChevronRight className="w-4 h-4" />السابق
                </Button>
                <span className="text-sm text-muted-foreground">
                  {page + 1} / {totalPages}
                </span>
                <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>
                  التالي<ChevronLeft className="w-4 h-4" />
                </Button>
              </div>
            )}
          </>
        )}
      </div>
    </CustomerLayout>
  );
}
