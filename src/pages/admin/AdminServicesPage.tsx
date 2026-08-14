import React, { useCallback, useEffect, useState } from 'react';
import { Settings, Loader2, Save, RefreshCw, Wifi, WifiOff, Search, X } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import type { ProviderService } from '@/types/types';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 25;

interface EditRow {
  customer_price: string;
  store_enabled: boolean;
  description_ar: string;
}

export default function AdminServicesPage() {
  const [services, setServices] = useState<ProviderService[]>([]);
  const [edits, setEdits] = useState<Record<string, EditRow>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<Record<string, boolean>>({});
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [total, setTotal] = useState(0);
  const [search, setSearch] = useState('');

  const initEdits = (rows: ProviderService[]) => {
    const init: Record<string, EditRow> = {};
    rows.forEach(s => {
      init[s.id] = {
        customer_price: s.customer_price?.toString() ?? '',
        store_enabled: s.store_enabled ?? false,
        description_ar: s.description_ar ?? '',
      };
    });
    return init;
  };

  const load = useCallback(async (p: number, q: string) => {
    setLoading(true);
    let query = supabase
      .from('provider_services')
      .select('*', { count: 'exact' })
      .order('name', { ascending: true })
      .range((p - 1) * PAGE_SIZE, p * PAGE_SIZE - 1);
    if (q.trim()) {
      query = query.or(`name.ilike.%${q.trim()}%,provider_code.ilike.%${q.trim()}%`);
    }
    const { data, count } = await query;
    const rows = (data ?? []) as ProviderService[];
    setServices(rows);
    setTotal(count ?? 0);
    setHasMore(rows.length === PAGE_SIZE);
    setEdits(initEdits(rows));
    setLoading(false);
  }, []);

  useEffect(() => { load(page, search); }, [page, load]);

  // Debounced search
  useEffect(() => {
    const t = setTimeout(() => { setPage(1); load(1, search); }, 350);
    return () => clearTimeout(t);
  }, [search, load]);

  // Realtime subscription
  useEffect(() => {
    const channel = supabase
      .channel('admin-services-realtime')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'provider_services' }, () => {
        load(page, search);
      })
      .subscribe();
    return () => { supabase.removeChannel(channel); };
  }, [page, search, load]);

  const handleSave = async (svc: ProviderService) => {
    const e = edits[svc.id];
    if (!e) return;
    const price = parseFloat(e.customer_price);
    if (e.customer_price && isNaN(price)) { toast.error('السعر غير صحيح'); return; }
    setSaving(s => ({ ...s, [svc.id]: true }));
    const { error } = await supabase
      .from('provider_services')
      .update({
        customer_price: e.customer_price ? price : null,
        store_enabled: e.store_enabled,
        description_ar: e.description_ar || null,
      })
      .eq('id', svc.id);
    setSaving(s => ({ ...s, [svc.id]: false }));
    if (error) toast.error('فشل الحفظ: ' + error.message);
    else toast.success(`✅ تم حفظ: ${svc.name}`);
  };

  const updateEdit = (id: string, field: keyof EditRow, value: string | boolean) => {
    setEdits(e => ({ ...e, [id]: { ...e[id], [field]: value } }));
  };

  const activeCount = services.filter(s => s.status === 'active').length;

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-5">
        {/* رأس الصفحة */}
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="space-y-0.5">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Settings className="w-5 h-5 text-primary" /> إدارة الخدمات
            </h1>
            <p className="text-sm text-muted-foreground">
              تحديد سعر البيع وتفعيل/إخفاء الخدمات في المتجر
              {!loading && total > 0 && (
                <span className="mr-2 text-primary font-medium">({total} خدمة)</span>
              )}
            </p>
          </div>
          <Button variant="secondary" size="sm" className="gap-1.5 shrink-0" onClick={() => load(page, search)} disabled={loading}>
            <RefreshCw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
            تحديث
          </Button>
        </div>

        {/* بحث */}
        <div className="relative max-w-sm">
          <Search className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
          <Input
            placeholder="بحث بالاسم أو الكود…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="bg-card border-border pr-9 text-sm"
          />
          {search && (
            <button onClick={() => setSearch('')}
              className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>

        <Card className="bg-card border-border">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex justify-center py-14">
                <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
              </div>
            ) : services.length === 0 ? (
              <div className="text-center py-14 space-y-2">
                <WifiOff className="w-8 h-8 text-muted-foreground mx-auto" />
                <p className="text-sm text-muted-foreground">
                  {search ? 'لا توجد نتائج للبحث' : 'لا توجد خدمات — قم بالمزامنة من لوحة المزود أولاً.'}
                </p>
              </div>
            ) : (
              <div className="overflow-x-auto w-full max-w-full">
                <table className="w-full min-w-max text-sm">
                  <thead>
                    <tr className="border-b border-border">
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">#</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">الخدمة</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">حالة المزود</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">تكلفة المزود</th>
                      <th className="text-end py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">سعر العميل</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">وصف (عربي)</th>
                      <th className="text-start py-3 px-4 text-xs text-muted-foreground font-medium whitespace-nowrap">المتجر</th>
                      <th className="py-3 px-4"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {services.map((svc, idx) => {
                      const e = edits[svc.id];
                      if (!e) return null;
                      return (
                        <tr key={svc.id} className="border-b border-border/40 hover:bg-muted/20 transition-colors">
                          <td className="py-3 px-4 whitespace-nowrap text-xs text-muted-foreground">
                            {(page - 1) * PAGE_SIZE + idx + 1}
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap max-w-56">
                            <div className="min-w-0">
                              <p className="text-xs font-medium text-foreground truncate">{svc.name}</p>
                              <p className="text-xs font-mono text-muted-foreground">{svc.provider_code}</p>
                            </div>
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap">
                            <span className={cn(
                              'text-xs px-2 py-0.5 rounded border font-medium',
                              svc.status === 'active'
                                ? 'text-green-400 bg-green-400/10 border-green-400/20'
                                : 'text-yellow-400 bg-yellow-400/10 border-yellow-400/20'
                            )}>
                              {svc.status === 'active' ? '🟢 نشط' : `⚠️ ${svc.status}`}
                            </span>
                          </td>
                          <td className="py-3 px-4 text-end text-xs text-muted-foreground whitespace-nowrap">
                            {svc.provider_credit_price != null ? `${svc.provider_credit_price.toLocaleString()} ك` : '—'}
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap">
                            <Input
                              type="number" step="0.0001" min="0" placeholder="0.0000"
                              value={e.customer_price}
                              onChange={ev => updateEdit(svc.id, 'customer_price', ev.target.value)}
                              className="bg-background border-border h-7 text-xs w-24 text-end"
                            />
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap">
                            <Input
                              placeholder="وصف الخدمة…"
                              value={e.description_ar}
                              onChange={ev => updateEdit(svc.id, 'description_ar', ev.target.value)}
                              className="bg-background border-border h-7 text-xs w-44"
                            />
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap">
                            <div className="flex items-center gap-2">
                              <Switch
                                checked={e.store_enabled}
                                onCheckedChange={v => updateEdit(svc.id, 'store_enabled', v)}
                                disabled={svc.status !== 'active'}
                              />
                              <span className={cn('text-xs', e.store_enabled ? 'text-green-400' : 'text-muted-foreground')}>
                                {e.store_enabled ? 'مفعّل' : 'مخفي'}
                              </span>
                            </div>
                          </td>
                          <td className="py-3 px-4 whitespace-nowrap">
                            <Button size="sm" variant="secondary" className="h-7 text-xs gap-1"
                              disabled={saving[svc.id]} onClick={() => handleSave(svc)}>
                              {saving[svc.id]
                                ? <Loader2 className="w-3 h-3 animate-spin" />
                                : <Save className="w-3 h-3" />}
                              حفظ
                            </Button>
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

        {/* Pagination */}
        {!loading && total > PAGE_SIZE && (
          <div className="flex items-center justify-between">
            <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
              السابق
            </Button>
            <span className="text-xs text-muted-foreground">
              صفحة {page} من {Math.ceil(total / PAGE_SIZE)}
            </span>
            <Button variant="ghost" size="sm" disabled={!hasMore} onClick={() => setPage(p => p + 1)}>
              التالي
            </Button>
          </div>
        )}
      </div>
    </AdminLayout>
  );
}
