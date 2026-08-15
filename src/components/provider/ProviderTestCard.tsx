import React, { useState } from 'react';
import {
  FlaskConical, CheckCircle2, XCircle, Loader2, Clock,
  Zap, Wifi, WifiOff, Coins, BarChart3,
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { toast } from 'sonner';
import { runLiveTest } from '@/lib/api';
import type { LiveTestResult } from '@/types/types';
import { cn } from '@/lib/utils';

interface Props {
  onTestComplete?: () => void;
}

function StepRow({ step }: { step: LiveTestResult['steps'][number] }) {
  const pass = step.result === 'PASS';
  return (
    <div className="flex items-center justify-between py-2.5 border-b border-border last:border-0 gap-4">
      <div className="flex items-center gap-2 min-w-0">
        {pass
          ? <CheckCircle2 className="w-4 h-4 text-green-400 shrink-0" />
          : <XCircle className="w-4 h-4 text-destructive shrink-0" />}
        <span className="text-sm text-foreground truncate">{step.name}</span>
      </div>
      <div className="flex items-center gap-3 shrink-0">
        <span className={cn('text-xs font-semibold', pass ? 'text-green-400' : 'text-destructive')}>
          {pass ? 'PASS' : 'FAIL'}
        </span>
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <Zap className="w-3 h-3" />{step.response_time_ms}ms
        </span>
        {step.http_status > 0 && (
          <span className="text-xs text-muted-foreground font-mono">{step.http_status}</span>
        )}
      </div>
    </div>
  );
}

export function ProviderTestCard({ onTestComplete }: Props) {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<LiveTestResult | null>(null);

  const handleTest = async () => {
    setLoading(true);
    setResult(null);
    try {
      const res = await runLiveTest();
      setResult(res);
      if (res.overall === 'PASS') {
        toast.success('🟢 الاتصال Live ناجح — جميع الاختبارات اجتازت');
      } else {
        toast.warning('🔴 فشل بعض الاختبارات — راجع النتائج أدناه');
      }
      onTestComplete?.();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'فشل الاختبار');
    } finally {
      setLoading(false);
    }
  };

  const isLive = result?.environment === 'live';

  return (
    <Card className="bg-card border-border">
      <CardHeader className="pb-3">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-2 min-w-0">
            <FlaskConical className="w-4 h-4 text-primary shrink-0" />
            <CardTitle className="text-sm font-semibold">اختبار الاتصال Live</CardTitle>
          </div>
          <Button variant="default" size="sm" className="h-8 gap-1.5 text-xs shrink-0"
            onClick={handleTest} disabled={loading}>
            {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FlaskConical className="w-3.5 h-3.5" />}
            {loading ? 'جارٍ الاختبار…' : 'تشغيل اختبار Live'}
          </Button>
        </div>
      </CardHeader>

      <CardContent>
        {!result && !loading ? (
          <div className="py-6 text-center">
            <FlaskConical className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
            <p className="text-sm text-muted-foreground">
              يُشغّل اختبارات حقيقية: GET /balance، GET /services، GET /stats/orders، GET /stats/services
            </p>
            <p className="text-xs text-muted-foreground mt-1 text-amber-400">
              ⚠️ لن يُنشئ أي طلب Live أو يستهلك رصيداً.
            </p>
          </div>
        ) : loading ? (
          <div className="py-6 text-center flex flex-col items-center gap-2">
            <Loader2 className="w-6 h-6 text-primary animate-spin" />
            <p className="text-sm text-muted-foreground">جارٍ تشغيل 4 اختبارات على Live API…</p>
          </div>
        ) : result ? (
          <div className="space-y-4">
            {/* Overall banner */}
            <div className={cn('flex items-center justify-between p-3 rounded border',
              result.overall === 'PASS' ? 'border-green-400/20 bg-green-400/5' : 'border-destructive/20 bg-destructive/5')}>
              <div className="flex items-center gap-2">
                {result.overall === 'PASS'
                  ? <><Wifi className="w-5 h-5 text-green-400" /><span className="font-semibold text-green-400">🟢 متصل</span></>
                  : <><WifiOff className="w-5 h-5 text-destructive" /><span className="font-semibold text-destructive">🔴 غير متصل</span></>}
              </div>
              <div className="flex items-center gap-3 shrink-0 text-xs text-muted-foreground">
                <span className={isLive ? 'text-red-400 font-medium' : 'text-amber-400'}>
                  {isLive ? '🔴 Live' : '🧪 Sandbox'}
                </span>
                {result.avg_response_ms > 0 && (
                  <span className="flex items-center gap-1">
                    <Zap className="w-3 h-3" />{result.avg_response_ms}ms
                  </span>
                )}
                <span className="flex items-center gap-1">
                  <Clock className="w-3 h-3" />
                  {new Date(result.tested_at).toLocaleTimeString('ar-SA')}
                </span>
              </div>
            </div>

            {/* 4 step rows */}
            <div>{result.steps.map(s => <StepRow key={s.name} step={s} />)}</div>

            {/* Live data summary on success */}
            {result.overall === 'PASS' && result.live_data && (
              <div className="grid grid-cols-2 gap-3">
                {result.live_data.balance && (
                  <div className="rounded border border-border bg-muted/20 p-3 space-y-0.5">
                    <p className="text-xs text-muted-foreground flex items-center gap-1">
                      <Coins className="w-3 h-3" /> الرصيد
                    </p>
                    <p className="text-sm font-semibold text-primary">
                      {result.live_data.balance.credit?.toLocaleString() ?? '—'} {result.live_data.balance.currency}
                    </p>
                  </div>
                )}
                <div className="rounded border border-border bg-muted/20 p-3 space-y-0.5">
                  <p className="text-xs text-muted-foreground flex items-center gap-1">
                    <BarChart3 className="w-3 h-3" /> الخدمات
                  </p>
                  <p className="text-sm font-semibold text-primary">
                    {result.live_data.services_count} خدمة
                    {result.live_data.services_available != null && (
                      <span className="text-xs text-green-400 mr-1">({result.live_data.services_available} متاح)</span>
                    )}
                  </p>
                </div>
                {result.live_data.orders_total != null && (
                  <div className="rounded border border-border bg-muted/20 p-3 space-y-0.5 col-span-2">
                    <p className="text-xs text-muted-foreground">إحصاءات الطلبات</p>
                    <p className="text-sm text-foreground">
                      {result.live_data.orders_total} إجمالي
                      {result.live_data.orders_active != null && (
                        <span className="text-xs text-amber-400 mr-2">· {result.live_data.orders_active} نشط</span>
                      )}
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* Request ID */}
            {result.last_request_id && (
              <p className="text-xs text-muted-foreground font-mono truncate">
                Request ID: {result.last_request_id}
              </p>
            )}

            {/* Errors */}
            {result.steps.some(s => s.error) && (
              <div className="space-y-1">
                {result.steps.filter(s => s.error).map(s => (
                  <div key={s.name} className="text-xs text-destructive bg-destructive/5 border border-destructive/20 rounded px-3 py-2">
                    <span className="font-medium">{s.name}</span>: {s.error}
                  </div>
                ))}
              </div>
            )}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
