import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ClipboardList, Search, Loader2, ArrowLeft } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { OrderStatusBadge } from '@/components/customer/OrderStatusBadge';
import type { Order } from '@/types/types';

const PAGE_SIZE = 20;

export default function AdminOrdersPage() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);

  const load = useCallback(async (p: number, q: string) => {
    setLoading(true);
    let query = supabase
      .from('orders')
      .select('id, reference, status, customer_total, provider_cost, quantity, created_at, provider_task_id, provider_services!service_id(name), profiles!customer_id(email)')
      .order('created_at', { ascending: false })
      .range((p - 1) * PAGE_SIZE, p * PAGE_SIZE - 1);
    if (q.trim()) query = query.ilike('reference', `%${q.trim()}%`);
    const { data } = await query;
    const rows = (data ?? []) as unknown as Order[];
    setOrders(rows);
    setHasMore(rows.length === PAGE_SIZE);
    setLoading(false);
  }, []);

  useEffect(() => { load(page, search); }, [page, search, load]);

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-5">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="space-y-0.5">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <ClipboardList className="w-5 h-5 text-primary" /> الطلبات
            </h1>
            <p className="text-sm text-muted-foreground">جميع طلبات العملاء</p>
          </div>
          <div className="relative">
            <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <Input placeholder="ابحث برقم الطلب…" value={search}
              onChange={e => { setSearch(e.target.value); setPage(1); }}
              className="pe-10 bg-card border-border w-56" />
          </div>
        </div>

        <Card className="bg-card border-border">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-10">
                <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
              </div>
            ) : orders.length === 0 ? (
              <div className="text-center py-10">
                <ClipboardList className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
                <p className="text-sm text-muted-foreground">لا توجد طلبات</p>
              </div>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">رقم الطلب</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">العميل</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الخدمة</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الحالة</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">سعر العميل</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">تكلفة المزود</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">Task ID</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">التاريخ</th>
                      <th className="py-3 px-4"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map(o => (
                      <tr key={o.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                        <td className="py-2.5 px-4 font-mono text-xs text-primary whitespace-nowrap">{o.reference}</td>
                        <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap">
                          {(o as any).profiles?.email ?? '—'}
                        </td>
                        <td className="py-2.5 px-4 text-foreground whitespace-nowrap max-w-36 truncate text-xs">
                          {(o as any).provider_services?.name ?? '—'}
                        </td>
                        <td className="py-2.5 px-4 whitespace-nowrap"><OrderStatusBadge status={o.status} /></td>
                        <td className="py-2.5 px-4 text-end text-xs font-semibold text-primary whitespace-nowrap">
                          {o.customer_total?.toLocaleString('ar-SA')} ك
                        </td>
                        <td className="py-2.5 px-4 text-end text-xs text-muted-foreground whitespace-nowrap">
                          {o.provider_cost?.toLocaleString('ar-SA')} ك
                        </td>
                        <td className="py-2.5 px-4 font-mono text-xs text-muted-foreground whitespace-nowrap max-w-28 truncate">
                          {o.provider_task_id ?? '—'}
                        </td>
                        <td className="py-2.5 px-4 text-xs text-muted-foreground whitespace-nowrap">
                          {new Date(o.created_at).toLocaleDateString('ar-SA')}
                        </td>
                        <td className="py-2.5 px-4 whitespace-nowrap">
                          <Link to={`/admin/orders/${o.id}`}
                            className="text-xs text-primary hover:underline flex items-center gap-1">
                            تفاصيل <ArrowLeft className="w-3 h-3" />
                          </Link>
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
      </div>
    </AdminLayout>
  );
}
