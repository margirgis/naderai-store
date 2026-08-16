import React, { useCallback, useEffect, useState } from 'react';
import { Users, Search, Loader2, TrendingUp, TrendingDown, UserCheck, UserX } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { Profile } from '@/types/types';

const PAGE_SIZE = 15;

export default function AdminCustomersPage() {
  const [customers, setCustomers] = useState<Profile[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);

  const load = useCallback(async (p: number, q: string) => {
    setLoading(true);
    let query = supabase
      .from('profiles')
      .select('*')
      .eq('role', 'user')
      .order('created_at', { ascending: false })
      .range((p - 1) * PAGE_SIZE, p * PAGE_SIZE - 1);
    if (q.trim()) query = query.ilike('email', `%${q.trim()}%`);
    const { data } = await query;
    const rows = (data ?? []) as Profile[];
    setCustomers(rows);
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
              <Users className="w-5 h-5 text-primary" /> العملاء
            </h1>
            <p className="text-sm text-muted-foreground">إدارة حسابات العملاء</p>
          </div>
          <div className="relative">
            <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground" />
            <Input placeholder="ابحث بالبريد…" value={search}
              onChange={e => { setSearch(e.target.value); setPage(1); }}
              className="pe-10 bg-card border-border w-56" />
          </div>
        </div>

        <Card className="bg-card border-border">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-10"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : customers.length === 0 ? (
              <div className="text-center py-10">
                <Users className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
                <p className="text-sm text-muted-foreground">لا يوجد عملاء</p>
              </div>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">البريد الإلكتروني</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الحالة</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الرصيد</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">تاريخ التسجيل</th>
                    </tr>
                  </thead>
                  <tbody>
                    {customers.map(c => (
                      <tr key={c.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                        <td className="py-3 px-4 text-foreground whitespace-nowrap">{c.email ?? '—'}</td>
                        <td className="py-3 px-4 whitespace-nowrap">
                          <span className={`inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded border font-medium
                            ${c.status === 'active'
                              ? 'text-green-400 bg-green-400/10 border-green-400/20'
                              : 'text-destructive bg-destructive/10 border-destructive/20'}`}>
                            {c.status === 'active' ? <><UserCheck className="w-3 h-3" />نشط</> : <><UserX className="w-3 h-3" />موقوف</>}
                          </span>
                        </td>
                        <td className="py-3 px-4 text-end font-semibold text-primary whitespace-nowrap">
                          {(c.wallet_balance ?? 0).toLocaleString('ar-SA')} ك
                        </td>
                        <td className="py-3 px-4 text-xs text-muted-foreground whitespace-nowrap">
                          {new Date(c.created_at).toLocaleDateString('ar-SA')}
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
