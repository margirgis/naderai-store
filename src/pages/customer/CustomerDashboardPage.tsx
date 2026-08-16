import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Wallet, ClipboardList, CheckCircle2, Loader2, ArrowLeft,
  Clock, Sparkles, HardDrive, Cpu, Zap,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { CustomerLayout } from '@/components/layouts/CustomerLayout';
import { useAuth } from '@/contexts/AuthContext';
import { supabase } from '@/db/supabase';
import type { Order, ProviderService } from '@/types/types';
import { OrderStatusBadge } from '@/components/customer/OrderStatusBadge';

const GEMINI_HIGHLIGHTS = [
  { icon: Cpu,       text: 'Google Gemini AI كامل' },
  { icon: HardDrive, text: '5 تيرابايت تخزين' },
  { icon: Zap,       text: '1000 AI Credit / شهر' },
  { icon: Clock,     text: '18 شهراً متواصلة' },
];

interface Stats { total: number; active: number; success: number }

export default function CustomerDashboardPage() {
  const navigate = useNavigate();
  const { profile } = useAuth();
  const [stats, setStats] = useState<Stats>({ total: 0, active: 0, success: 0 });
  const [recent, setRecent] = useState<Order[]>([]);
  const [featuredSvc, setFeaturedSvc] = useState<ProviderService | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      const [{ data: orders }, { data: active }, { data: success }, { data: svcData }] = await Promise.all([
        supabase.from('orders')
          .select('id, status, customer_total, reference, created_at, provider_services!service_id(name)')
          .order('created_at', { ascending: false }).limit(5),
        supabase.from('orders').select('id', { count: 'exact' })
          .in('status', ['creating', 'queued', 'processing']),
        supabase.from('orders').select('id', { count: 'exact' }).eq('status', 'success'),
        supabase.from('provider_services').select('*')
          .eq('store_enabled', true).eq('status', 'active').limit(1).maybeSingle(),
      ]);
      setRecent((orders ?? []) as unknown as Order[]);
      setFeaturedSvc(svcData as ProviderService ?? null);
      setStats({
        total: orders?.length ?? 0,
        active: active?.length ?? 0,
        success: success?.length ?? 0,
      });
      setLoading(false);
    };
    load();
  }, []);

  const statCards = [
    { label: 'رصيدي', value: `${(profile?.wallet_balance ?? 0).toFixed(1)} Credit`, icon: Wallet, color: 'text-primary' },
    { label: 'طلبات نشطة', value: stats.active, icon: Clock, color: 'text-amber-500' },
    { label: 'طلبات مكتملة', value: stats.success, icon: CheckCircle2, color: 'text-green-600' },
    { label: 'إجمالي طلباتي', value: stats.total, icon: ClipboardList, color: 'text-foreground' },
  ];

  return (
    <CustomerLayout>
      <div className="px-4 md:px-6 py-6 max-w-3xl mx-auto space-y-6">

        {/* Hero */}
        <div className="relative overflow-hidden rounded-2xl bg-primary px-6 py-8 text-white shadow-md">
          <div className="relative z-10 space-y-2">
            <p className="text-sm font-medium text-white/80">
              مرحباً{profile?.email ? `، ${profile.email.split('@')[0]}` : ''} 👋
            </p>
            <h1 className="text-2xl font-bold text-balance">مرحبًا بك في Nader AI</h1>
            <p className="text-sm text-white/80">خدمات رقمية باشتراكات موثوقة وسهلة التفعيل</p>
          </div>
          {/* Decorative circles */}
          <div className="absolute top-0 left-0 w-40 h-40 rounded-full bg-white/5 -translate-x-12 -translate-y-12 pointer-events-none" />
          <div className="absolute bottom-0 right-0 w-32 h-32 rounded-full bg-white/5 translate-x-8 translate-y-8 pointer-events-none" />
        </div>

        {/* Stats */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {statCards.map(({ label, value, icon: Icon, color }) => (
            <Card key={label} className="bg-card border-border shadow-sm">
              <CardContent className="p-4 space-y-2">
                <Icon className={`w-5 h-5 ${color}`} />
                <p className="text-xl font-bold text-foreground">{value}</p>
                <p className="text-xs text-muted-foreground">{label}</p>
              </CardContent>
            </Card>
          ))}
        </div>

        {/* Featured service — Gemini Pro 18M */}
        {featuredSvc && (
          <div className="bg-card border border-border rounded-2xl overflow-hidden shadow-sm">
            <div className="h-1 bg-primary" />
            <div className="p-5 space-y-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <Badge className="bg-primary/10 text-primary border-primary/20 text-xs mb-2">الخدمة المميزة</Badge>
                  <h2 className="text-lg font-bold text-foreground">
                    {featuredSvc.display_name_ar ?? 'جيميناي برو 18 شهر'}
                  </h2>
                  <p className="text-sm text-muted-foreground">
                    {featuredSvc.display_name_en ?? 'Gemini AI Pro — 18 Months'}
                  </p>
                </div>
                <p className="text-2xl font-bold text-primary shrink-0">
                  {(featuredSvc.customer_price ?? featuredSvc.final_credit_price ?? 0).toFixed(1)}
                  <span className="text-xs font-normal text-muted-foreground"> Credit</span>
                </p>
              </div>

              <div className="flex flex-wrap gap-2">
                {GEMINI_HIGHLIGHTS.map(({ icon: Icon, text }) => (
                  <span key={text} className="flex items-center gap-1 text-xs text-muted-foreground bg-muted/50 px-2.5 py-1 rounded-full border border-border">
                    <Icon className="w-3 h-3 text-primary shrink-0" />
                    {text}
                  </span>
                ))}
              </div>

              <div className="flex gap-2">
                <Button className="flex-1 gap-2 font-semibold" onClick={() => navigate(`/store/order/${featuredSvc.id}`)}>
                  <Sparkles className="w-4 h-4" />
                  اشتراك الآن
                </Button>
                <Button variant="outline" onClick={() => navigate('/store/services')}>
                  التفاصيل
                </Button>
              </div>
            </div>
          </div>
        )}

        {/* Recent orders */}
        <Card className="bg-card border-border shadow-sm">
          <CardHeader className="pb-3 flex flex-row items-center justify-between">
            <CardTitle className="text-sm font-semibold">آخر الطلبات</CardTitle>
            <Link to="/store/orders" className="text-xs text-primary hover:underline flex items-center gap-1">
              عرض الكل <ArrowLeft className="w-3 h-3" />
            </Link>
          </CardHeader>
          <CardContent>
            {loading ? (
              <div className="flex justify-center py-6"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>
            ) : recent.length === 0 ? (
              <div className="text-center py-6 space-y-2">
                <ClipboardList className="w-8 h-8 text-muted-foreground mx-auto" />
                <p className="text-sm text-muted-foreground">لا توجد طلبات بعد</p>
                <Button size="sm" asChild><Link to="/store/services">اطلب أول خدمة</Link></Button>
              </div>
            ) : (
              <div className="space-y-2">
                {recent.map(o => {
                  const svcR = (o as any).provider_services;
                  const namAr = svcR?.display_name_ar ?? svcR?.name ?? 'خدمة';
                  return (
                    <Link key={o.id} to={`/store/orders/${o.id}`}
                      className="flex items-center justify-between p-3 rounded-xl border border-border hover:border-primary/30 hover:bg-muted/20 transition-colors gap-3">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-foreground truncate">{namAr}</p>
                        <p className="text-xs text-muted-foreground font-mono">{o.reference}</p>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        <span className="text-xs text-primary font-semibold">{(o.customer_total ?? 0).toFixed(1)} Credit</span>
                        <OrderStatusBadge status={o.status} />
                      </div>
                    </Link>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </CustomerLayout>
  );
}
