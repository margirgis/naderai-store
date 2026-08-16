import React, { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Plug, Shield, Wifi, WifiOff, Coins, Settings,
  Users, ClipboardList, RefreshCw, FlaskConical, Clock,
  CheckCircle2, AlertCircle, Loader2,
} from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { useAuth } from '@/contexts/AuthContext';
import { supabase } from '@/db/supabase';
import { cn } from '@/lib/utils';
import type { ProviderConfig } from '@/types/types';

interface DashStats {
  config: ProviderConfig | null;
  totalServices: number;
  availableServices: number;
  totalOrders: number;
  pendingOrders: number;
  totalCustomers: number;
  lastSyncedAt: string | null;
}

const EMPTY_STATS: DashStats = {
  config: null,
  totalServices: 0,
  availableServices: 0,
  totalOrders: 0,
  pendingOrders: 0,
  totalCustomers: 0,
  lastSyncedAt: null,
};

export default function DashboardPage() {
  const navigate = useNavigate();
  const { profile } = useAuth();
  const [stats, setStats] = useState<DashStats>(EMPTY_STATS);
  const [loading, setLoading] = useState(true);

  const loadStats = useCallback(async () => {
    setLoading(true);
    try {
      const [
        { data: cfg },
        { count: totalSvc },
        { count: availSvc },
        { count: totalOrd },
        { count: pendOrd },
        { count: totalCust },
        { data: lastSync },
      ] = await Promise.all([
        supabase.from('provider_config').select('*').limit(1).maybeSingle(),
        supabase.from('provider_services').select('*', { count: 'exact', head: true }),
        supabase.from('provider_services').select('*', { count: 'exact', head: true }).eq('status', 'active').eq('store_enabled', true),
        supabase.from('orders').select('*', { count: 'exact', head: true }),
        supabase.from('orders').select('*', { count: 'exact', head: true }).in('status', ['creating', 'queued', 'processing']),
        supabase.from('profiles').select('*', { count: 'exact', head: true }).eq('role', 'user'),
        supabase.from('provider_services').select('last_synced_at').order('last_synced_at', { ascending: false }).limit(1),
      ]);
      setStats({
        config: cfg as ProviderConfig | null,
        totalServices: totalSvc ?? 0,
        availableServices: availSvc ?? 0,
        totalOrders: totalOrd ?? 0,
        pendingOrders: pendOrd ?? 0,
        totalCustomers: totalCust ?? 0,
        lastSyncedAt: lastSync?.[0]?.last_synced_at ?? null,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadStats();

    // Realtime: provider_config changes (balance, status)
    const cfgChannel = supabase
      .channel('dashboard-provider-config')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'provider_config' }, () => loadStats())
      .subscribe();

    // Realtime: provider_services changes
    const svcChannel = supabase
      .channel('dashboard-provider-services')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'provider_services' }, () => loadStats())
      .subscribe();

    // Realtime: orders changes
    const ordChannel = supabase
      .channel('dashboard-orders')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'orders' }, () => loadStats())
      .subscribe();

    return () => {
      supabase.removeChannel(cfgChannel);
      supabase.removeChannel(svcChannel);
      supabase.removeChannel(ordChannel);
    };
  }, [loadStats]);

  const connected = stats.config?.last_health_check_success;
  const balance = stats.config?.balance_credit;
  const balanceCurrency = stats.config?.balance_currency ?? 'CREDIT';
  const environment = stats.config?.environment ?? 'sandbox';
  const lastCheck = stats.config?.last_health_check_at
    ? new Date(stats.config.last_health_check_at).toLocaleTimeString('ar-SA')
    : null;

  const statCards = [
    {
      label: 'رصيد المزود',
      value: balance != null ? `${Number(balance).toLocaleString()} ${balanceCurrency}` : '—',
      icon: Coins,
      color: 'text-primary',
      onClick: () => navigate('/admin/provider'),
    },
    {
      label: 'إجمالي الخدمات',
      value: loading ? '…' : stats.totalServices,
      icon: Settings,
      color: 'text-blue-400',
      onClick: () => navigate('/admin/services'),
    },
    {
      label: 'خدمات متاحة',
      value: loading ? '…' : stats.availableServices,
      icon: CheckCircle2,
      color: 'text-green-400',
      onClick: () => navigate('/admin/services'),
    },
    {
      label: 'إجمالي الطلبات',
      value: loading ? '…' : stats.totalOrders,
      icon: ClipboardList,
      color: 'text-foreground',
      onClick: () => navigate('/admin/orders'),
    },
    {
      label: 'طلبات نشطة',
      value: loading ? '…' : stats.pendingOrders,
      icon: AlertCircle,
      color: 'text-yellow-400',
      onClick: () => navigate('/admin/orders'),
    },
    {
      label: 'إجمالي العملاء',
      value: loading ? '…' : stats.totalCustomers,
      icon: Users,
      color: 'text-purple-400',
      onClick: () => navigate('/admin/customers'),
    },
  ];

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6 max-w-5xl mx-auto">

        {/* رأس الصفحة */}
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Shield className="w-3 h-3 text-primary" />
              <span className="tracking-widest">Nader AI — لوحة الإدارة</span>
            </div>
            <h1 className="text-xl font-bold text-foreground text-balance">
              مرحباً{profile?.email ? `، ${profile.email.split('@')[0]}` : ''} 👋
            </h1>
            <p className="text-sm text-muted-foreground">نظرة عامة على نظام Nader AI</p>
          </div>
          <Button variant="secondary" size="sm" className="gap-1.5 shrink-0" onClick={loadStats} disabled={loading}>
            <RefreshCw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
            تحديث
          </Button>
        </div>

        {/* شريط حالة المزود */}
        <div
          className={cn(
            'flex items-center gap-3 px-4 py-3 rounded border cursor-pointer transition-colors',
            connected
              ? 'border-green-400/20 bg-green-400/5 hover:bg-green-400/10'
              : connected === false
                ? 'border-destructive/20 bg-destructive/5 hover:bg-destructive/10'
                : 'border-border bg-muted/20 hover:bg-muted/40'
          )}
          onClick={() => navigate('/admin/provider')}
        >
          {loading
            ? <Loader2 className="w-4 h-4 animate-spin text-muted-foreground shrink-0" />
            : connected
              ? <Wifi className="w-4 h-4 text-green-400 shrink-0" />
              : <WifiOff className="w-4 h-4 text-destructive shrink-0" />
          }
          <div className="flex-1 min-w-0">
            <span className={cn('text-sm font-semibold',
              connected ? 'text-green-400' : connected === false ? 'text-destructive' : 'text-muted-foreground')}>
              {loading ? 'جارٍ التحميل…' : connected ? '🟢 المزود متصل' : connected === false ? '🔴 المزود غير متصل' : '⚪ حالة المزود غير معروفة'}
            </span>
            {lastCheck && (
              <p className="text-xs text-muted-foreground flex items-center gap-1 mt-0.5">
                <Clock className="w-3 h-3" /> آخر فحص: {lastCheck}
              </p>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <Badge variant="outline" className="text-primary border-primary/30 bg-primary/10 text-xs">
              🧪 {environment === 'sandbox' ? 'Sandbox' : environment}
            </Badge>
            <Plug className="w-4 h-4 text-muted-foreground" />
          </div>
        </div>

        {/* بطاقات الإحصائيات */}
        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
          {statCards.map(({ label, value, icon: Icon, color, onClick }) => (
            <Card key={label}
              className="bg-card border-border cursor-pointer hover:border-primary/30 transition-colors"
              onClick={onClick}
            >
              <CardContent className="p-4 space-y-2">
                {loading
                  ? <Skeleton className="h-14 w-full bg-muted" />
                  : <>
                    <Icon className={cn('w-4 h-4', color)} />
                    <p className="text-xl font-bold text-foreground">{value}</p>
                    <p className="text-xs text-muted-foreground">{label}</p>
                  </>
                }
              </CardContent>
            </Card>
          ))}
        </div>

        {/* صف معلومات إضافية */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {/* آخر مزامنة */}
          <Card className="bg-card border-border">
            <CardHeader className="pb-2 pt-4 px-4">
              <CardTitle className="text-xs text-muted-foreground font-medium uppercase tracking-wide flex items-center gap-1.5">
                <FlaskConical className="w-3.5 h-3.5 text-primary" /> آخر مزامنة
              </CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-4">
              {loading
                ? <Skeleton className="h-8 w-full bg-muted" />
                : <p className="text-sm font-medium text-foreground">
                  {stats.lastSyncedAt
                    ? new Date(stats.lastSyncedAt).toLocaleString('ar-SA')
                    : '—  لم تتم مزامنة بعد'}
                </p>
              }
              <p className="text-xs text-muted-foreground mt-1">تلقائي كل 5 دقائق</p>
            </CardContent>
          </Card>

          {/* وصول سريع */}
          <Card className="bg-card border-border">
            <CardHeader className="pb-2 pt-4 px-4">
              <CardTitle className="text-xs text-muted-foreground font-medium uppercase tracking-wide">وصول سريع</CardTitle>
            </CardHeader>
            <CardContent className="px-4 pb-4 flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" className="h-7 text-xs gap-1" onClick={() => navigate('/admin/provider')}>
                <Plug className="w-3 h-3" /> المزود
              </Button>
              <Button size="sm" variant="secondary" className="h-7 text-xs gap-1" onClick={() => navigate('/admin/services')}>
                <Settings className="w-3 h-3" /> الخدمات
              </Button>
              <Button size="sm" variant="secondary" className="h-7 text-xs gap-1" onClick={() => navigate('/admin/orders')}>
                <ClipboardList className="w-3 h-3" /> الطلبات
              </Button>
              <Button size="sm" variant="secondary" className="h-7 text-xs gap-1" onClick={() => navigate('/admin/customers')}>
                <Users className="w-3 h-3" /> العملاء
              </Button>
            </CardContent>
          </Card>
        </div>

      </div>
    </AdminLayout>
  );
}
