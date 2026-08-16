import React from 'react';
import { ScrollText, CheckCircle2, XCircle, RefreshCw, Clock, Zap } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import type { ProviderLog } from '@/types/types';
import { cn } from '@/lib/utils';

const OPERATION_LABELS: Record<string, string> = {
  health_check: 'فحص الحالة',
  sync_services: 'مزامنة الخدمات',
  refresh_balance: 'تحديث الرصيد',
  test_authentication: 'اختبار: المصادقة',
  test_get_services: 'اختبار: الخدمات',
  test_get_balance: 'اختبار: الرصيد',
  test_connection: 'اختبار الاتصال',
};

interface Props {
  logs: ProviderLog[];
  total: number;
  onRefresh: () => void;
}

export function ProviderLogsCard({ logs, total, onRefresh }: Props) {
  return (
    <Card className="bg-card border-border">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            <ScrollText className="w-4 h-4 text-primary shrink-0" />
            <CardTitle className="text-sm font-semibold">
              سجلات المزود
              {total > 0 && <span className="ms-2 text-xs text-muted-foreground font-normal">({total} إجمالي)</span>}
            </CardTitle>
          </div>
          <Button variant="ghost" size="icon" className="h-7 w-7 shrink-0" onClick={onRefresh}>
            <RefreshCw className="w-3.5 h-3.5" />
          </Button>
        </div>
      </CardHeader>

      <CardContent>
        {logs.length === 0 ? (
          <div className="py-6 text-center">
            <ScrollText className="w-8 h-8 text-muted-foreground mx-auto mb-2" />
            <p className="text-sm text-muted-foreground">لا توجد سجلات بعد. قم بتشغيل اختبار أو مزامنة لإنشائها.</p>
          </div>
        ) : (
          <div className="overflow-x-auto w-full max-w-full">
            <table className="w-full min-w-max text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">العملية</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">النتيجة</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">HTTP</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الوقت</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">معرّف الطلب</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">الخطأ</th>
                  <th className="text-start py-2 px-3 text-xs text-muted-foreground font-medium whitespace-nowrap">التاريخ</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => (
                  <tr key={log.id} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                    <td className="py-2.5 px-3 text-foreground whitespace-nowrap text-xs font-medium">
                      {OPERATION_LABELS[log.operation] ?? log.operation}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap">
                      {log.success
                        ? <span className="flex items-center gap-1 text-green-400 text-xs"><CheckCircle2 className="w-3.5 h-3.5" />نجاح</span>
                        : <span className="flex items-center gap-1 text-destructive text-xs"><XCircle className="w-3.5 h-3.5" />فشل</span>
                      }
                    </td>
                    <td className="py-2.5 px-3 font-mono text-xs text-muted-foreground whitespace-nowrap">
                      {log.http_status ?? '—'}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap">
                      {log.response_time_ms != null ? (
                        <span className="flex items-center gap-1 text-xs text-muted-foreground">
                          <Zap className="w-3 h-3" />{log.response_time_ms}ms
                        </span>
                      ) : '—'}
                    </td>
                    <td className="py-2.5 px-3 font-mono text-xs text-muted-foreground whitespace-nowrap max-w-32 truncate">
                      {log.provider_request_id ?? '—'}
                    </td>
                    <td className="py-2.5 px-3 text-xs text-destructive whitespace-nowrap max-w-40 truncate">
                      {log.error_code ? `[${log.error_code}]` : ''} {log.error_message ?? ''}
                    </td>
                    <td className="py-2.5 px-3 whitespace-nowrap">
                      <span className="flex items-center gap-1 text-xs text-muted-foreground">
                        <Clock className="w-3 h-3 shrink-0" />
                        {new Date(log.created_at).toLocaleString('ar-SA')}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
