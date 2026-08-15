import React, { useEffect, useState, useCallback } from 'react';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { ProviderStatusCard } from '@/components/provider/ProviderStatusCard';
import { ProviderBalanceCard } from '@/components/provider/ProviderBalanceCard';
import { ProviderServicesCard } from '@/components/provider/ProviderServicesCard';
import { ProviderTestCard } from '@/components/provider/ProviderTestCard';
import { ProviderLogsCard } from '@/components/provider/ProviderLogsCard';
import { getProviderConfig, getServicesStats, getProviderLogs } from '@/lib/api';
import type { ProviderConfig, ProviderLog } from '@/types/types';
import { supabase } from '@/db/supabase';
import { Shield } from 'lucide-react';

export default function ProviderPanelPage() {
  const [config, setConfig] = useState<ProviderConfig | null>(null);
  const [stats, setStats] = useState({ total: 0, available: 0, maintenance: 0 });
  const [logs, setLogs] = useState<ProviderLog[]>([]);
  const [logsCount, setLogsCount] = useState(0);
  const [loadingConfig, setLoadingConfig] = useState(true);

  const loadData = useCallback(async () => {
    setLoadingConfig(true);
    try {
      const [cfg, st, lg] = await Promise.all([
        getProviderConfig(),
        getServicesStats(),
        getProviderLogs(1, 30),
      ]);
      setConfig(cfg);
      setStats(st);
      setLogs(lg.data);
      setLogsCount(lg.count);
    } finally {
      setLoadingConfig(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  // Realtime: auto-refresh when provider_config or services change
  useEffect(() => {
    const channel = supabase
      .channel('provider-panel-realtime')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'provider_config' },
        () => { loadData(); })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'provider_services' },
        async () => {
          const st = await getServicesStats();
          setStats(st);
        })
      .subscribe();
    return () => { supabase.removeChannel(channel); };
  }, [loadData]);

  const environment = config?.environment ?? 'live';
  const isLive = environment === 'live';

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6 max-w-5xl mx-auto">
        {/* رأس الصفحة */}
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <Shield className="w-3 h-3 text-primary" />
            <span className="tracking-widest">المسؤول · إعدادات المزود (Provider API)</span>
          </div>
          <h1 className="text-xl font-bold text-foreground text-balance">
            إعدادات المزود (Provider API)
          </h1>
          <p className="text-sm text-muted-foreground">
            {isLive
              ? '🔴 البيئة الحية (Live) — الطلبات الحقيقية نشطة.'
              : '🧪 بيئة الاختبار (Sandbox) — لا توجد عمليات مباشرة نشطة.'}
          </p>
        </div>

        {/* صف علوي: الحالة + الرصيد */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <ProviderStatusCard
            config={config}
            loading={loadingConfig}
            onRefresh={loadData}
          />
          <ProviderBalanceCard onBalanceRefreshed={loadData} />
        </div>

        {/* اختبار الاتصال Live */}
        <ProviderTestCard onTestComplete={loadData} />

        {/* الخدمات */}
        <ProviderServicesCard stats={stats} onSyncComplete={loadData} />

        {/* السجلات */}
        <ProviderLogsCard
          logs={logs}
          total={logsCount}
          onRefresh={async () => {
            const lg = await getProviderLogs(1, 30);
            setLogs(lg.data);
            setLogsCount(lg.count);
          }}
        />
      </div>
    </AdminLayout>
  );
}
