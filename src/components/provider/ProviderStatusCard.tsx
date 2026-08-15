import React, { useState } from 'react';
import {
  Wifi, WifiOff, Key, Clock, RefreshCw, Server, Eye, EyeOff,
  Loader2, ShieldCheck, Activity, Coins, Package, Zap,
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog';
import type { ProviderConfig } from '@/types/types';
import { updateApiKey } from '@/lib/api';
import { cn } from '@/lib/utils';
import { toast } from 'sonner';

interface Props {
  config: ProviderConfig | null;
  loading: boolean;
  onRefresh: () => void;
  maskedKey?: string;
}

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5 border-b border-border last:border-0">
      <span className="text-xs text-muted-foreground shrink-0">{label}</span>
      <span className="text-sm font-medium text-end min-w-0">{value}</span>
    </div>
  );
}

function ChangeKeyModal({ onSuccess }: { onSuccess: (maskedKey: string) => void }) {
  const [open, setOpen] = useState(false);
  const [newKey, setNewKey] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [loading, setLoading] = useState(false);
  const [stage, setStage] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newKey.trim() || newKey.trim().length < 10) { toast.error('المفتاح قصير جداً'); return; }
    setLoading(true);
    setStage('جارٍ التحقق من المفتاح…');
    try {
      const res = await updateApiKey(newKey.trim());
      if (res.success && res.key_updated) {
        toast.success('تم تحديث مفتاح API بنجاح والاتصال ناجح');
        setOpen(false); setNewKey('');
        onSuccess(res.masked_key ?? '');
      } else {
        const stageLabel =
          res.stage === 'authentication' ? 'فشلت المصادقة' :
          res.stage === 'get_services'   ? 'فشل جلب الخدمات' :
          res.stage === 'save'           ? 'فشل حفظ المفتاح' : 'فشل التحقق';
        toast.error(`${stageLabel}: ${res.error ?? 'خطأ غير معروف'}`);
      }
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'فشل تحديث المفتاح');
    } finally { setLoading(false); setStage(''); }
  };

  return (
    <Dialog open={open} onOpenChange={v => { setOpen(v); if (!v) { setNewKey(''); setShowKey(false); } }}>
      <DialogTrigger asChild>
        <Button variant="secondary" size="sm" className="h-7 text-xs gap-1.5 shrink-0">
          <Key className="w-3 h-3" /> تغيير مفتاح API
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-[calc(100%-2rem)] md:max-w-sm">
        <DialogHeader>
          <DialogTitle className="text-sm flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-primary" /> تغيير مفتاح API
          </DialogTitle>
        </DialogHeader>
        <div className="text-xs text-muted-foreground bg-muted/40 border border-border rounded p-3 space-y-1">
          <p>🔒 سيتم اختبار المفتاح الجديد قبل حفظه.</p>
          <p>🔒 المفتاح القديم يبقى نشطاً حتى نجاح الاختبار.</p>
          <p>🔒 المفتاح لن يظهر بعد الحفظ.</p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4 mt-1">
          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">مفتاح API الجديد</Label>
            <div className="relative">
              <Input type={showKey ? 'text' : 'password'}
                placeholder="gk_live_••••••••••••"
                value={newKey} onChange={e => setNewKey(e.target.value)}
                className="bg-background border-border ps-10 font-mono text-xs"
                autoComplete="off" required />
              <button type="button" tabIndex={-1} onClick={() => setShowKey(s => !s)}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground">
                {showKey ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              </button>
            </div>
          </div>
          {stage && (
            <p className="text-xs text-muted-foreground flex items-center gap-1.5">
              <Loader2 className="w-3 h-3 animate-spin" /> {stage}
            </p>
          )}
          <Button type="submit" className="w-full gap-1.5" disabled={loading || !newKey.trim()}>
            {loading && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
            {loading ? 'جارٍ الاختبار والحفظ…' : 'اختبار وحفظ المفتاح'}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function ProviderStatusCard({ config, loading, onRefresh, maskedKey }: Props) {
  const [currentMaskedKey, setCurrentMaskedKey] = useState(maskedKey ?? '');
  const keyStatus = config?.key_status ?? 'unknown';
  const lastCheck = config?.last_health_check_at
    ? new Date(config.last_health_check_at).toLocaleString('ar-SA') : '—';
  const lastSuccess = config?.last_health_check_success;
  const environment = config?.environment ?? 'live';
  const isLive = environment === 'live';
  const responseTimeMs = config?.last_response_time_ms;
  const lastRequestId = config?.last_request_id;
  const lastError = config?.last_error_message;

  const keyBadgeClass =
    keyStatus === 'valid'   ? 'text-green-400 bg-green-400/10 border-green-400/20' :
    keyStatus === 'invalid' ? 'text-destructive bg-destructive/10 border-destructive/20' :
                              'text-muted-foreground bg-muted/30 border-border';

  return (
    <Card className="bg-card border-border">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <div className="flex items-center gap-2">
            <Server className="w-4 h-4 text-primary shrink-0" />
            <CardTitle className="text-sm font-semibold">حالة الاتصال</CardTitle>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <Button variant="ghost" size="icon" className="h-7 w-7" onClick={onRefresh} disabled={loading}>
              <RefreshCw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
            </Button>
            <ChangeKeyModal onSuccess={mk => { setCurrentMaskedKey(mk); onRefresh(); }} />
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-0 px-4 pb-4">
        {loading ? (
          <div className="space-y-3">
            {[1,2,3,4,5,6].map(i => <Skeleton key={i} className="h-8 w-full bg-muted" />)}
          </div>
        ) : (
          <>
            {/* Connection status */}
            <InfoRow label="حالة الاتصال" value={
              <span className={cn('flex items-center gap-1.5 font-semibold',
                lastSuccess ? 'text-green-400' : lastSuccess === false ? 'text-destructive' : 'text-muted-foreground')}>
                {lastSuccess
                  ? <><Wifi className="w-3.5 h-3.5" /> 🟢 متصل</>
                  : lastSuccess === false
                    ? <><WifiOff className="w-3.5 h-3.5" /> 🔴 غير متصل</>
                    : <><WifiOff className="w-3.5 h-3.5" /> غير معروف</>}
              </span>
            } />
            {/* Environment */}
            <InfoRow label="البيئة" value={
              <Badge variant="outline" className={cn('text-xs tracking-wide border',
                isLive
                  ? 'text-red-400 border-red-400/30 bg-red-400/10'
                  : 'text-amber-400 border-amber-400/30 bg-amber-400/10')}>
                {isLive ? '🔴 Live' : '🧪 Sandbox'}
              </Badge>
            } />
            {/* Auth */}
            <InfoRow label="المصادقة" value={
              <span className={cn('text-xs font-medium',
                keyStatus === 'valid' ? 'text-green-400' :
                keyStatus === 'invalid' ? 'text-destructive' : 'text-muted-foreground')}>
                {keyStatus === 'valid' ? '✓ ناجحة' : keyStatus === 'invalid' ? '✗ فاشلة' : '— غير محددة'}
              </span>
            } />
            {/* API Key masked */}
            <InfoRow label="API Key" value={
              <div className="flex items-center gap-2 min-w-0">
                <span className={cn('text-xs px-2 py-0.5 rounded border font-medium shrink-0', keyBadgeClass)}>
                  {keyStatus === 'valid' ? '✓ مفتاح محفوظ' : keyStatus === 'invalid' ? '✗ غير صالح' : '— غير معروف'}
                </span>
                {(currentMaskedKey || maskedKey) && (
                  <code className="text-xs font-mono text-muted-foreground truncate">
                    {currentMaskedKey || maskedKey}
                  </code>
                )}
              </div>
            } />
            {/* Balance from config */}
            {config?.balance_credit != null && (
              <InfoRow label="الرصيد" value={
                <span className="flex items-center gap-1.5 text-primary font-semibold">
                  <Coins className="w-3.5 h-3.5" />
                  {config.balance_credit.toLocaleString()} {config.balance_currency ?? 'ك'}
                </span>
              } />
            )}
            {/* Services counts from config */}
            {config?.services_count != null && (
              <InfoRow label="الخدمات" value={
                <span className="flex items-center gap-2 text-xs">
                  <Package className="w-3.5 h-3.5 text-muted-foreground" />
                  <span className="text-foreground font-medium">{config.services_count} إجمالي</span>
                  {config.services_available != null && (
                    <span className="text-green-400">{config.services_available} متاح</span>
                  )}
                  {config.services_maintenance != null && config.services_maintenance > 0 && (
                    <span className="text-amber-400">{config.services_maintenance} صيانة</span>
                  )}
                </span>
              } />
            )}
            {/* Response time */}
            {responseTimeMs != null && (
              <InfoRow label="زمن الاستجابة" value={
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Activity className="w-3 h-3 shrink-0" /> {responseTimeMs}ms
                </span>
              } />
            )}
            {/* Last request_id */}
            {lastRequestId && (
              <InfoRow label="آخر Request ID" value={
                <code className="text-xs font-mono text-muted-foreground truncate max-w-40">{lastRequestId}</code>
              } />
            )}
            {/* Last check time */}
            <InfoRow label="آخر اختبار" value={
              <span className="flex items-center gap-1 text-muted-foreground text-xs">
                <Clock className="w-3 h-3 shrink-0" /> {lastCheck}
              </span>
            } />
            {/* Last error */}
            {lastError && !lastSuccess && (
              <InfoRow label="آخر خطأ" value={
                <span className="text-xs text-destructive truncate max-w-48">{lastError}</span>
              } />
            )}
            {/* Response time from Zap */}
            {responseTimeMs != null && (
              <InfoRow label="سرعة الاستجابة" value={
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Zap className="w-3 h-3 shrink-0" /> {responseTimeMs}ms
                </span>
              } />
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
