import React, { useCallback, useEffect, useState } from 'react';
import { Smartphone, RefreshCw, Wifi, WifiOff, Clock, Trash2, Cpu, Hash, Phone } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { AdminLayout } from '@/components/layouts/AdminLayout';
import { supabase } from '@/db/supabase';
import { toast } from 'sonner';
import type { SmsDevice, PendingTask } from '@/types/types';

export default function AdminSmsDevicesPage() {
  const [devices, setDevices] = useState<SmsDevice[]>([]);
  const [tasks, setTasks] = useState<PendingTask[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      await supabase.rpc('update_sms_device_statuses');
      const { data: devicesData, error: devicesError } = await supabase
        .from('sms_device_status')
        .select('*')
        .order('last_heartbeat_at', { ascending: false });
      if (devicesError) throw devicesError;
      setDevices((devicesData ?? []) as SmsDevice[]);

      const { data: tasksData } = await supabase
        .from('pending_tasks')
        .select('*')
        .in('task_status', ['pending', 'assigned', 'in_progress'])
        .order('created_at', { ascending: false });
      setTasks((tasksData ?? []) as PendingTask[]);
    } catch (err: any) {
      toast.error(err?.message || 'فشل تحميل حالة الأجهزة');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const interval = setInterval(load, 30_000);
    return () => clearInterval(interval);
  }, [load]);

  const deleteDevice = async (id: string) => {
    if (!confirm('هل تريد حذف هذا الجهاز من القائمة؟')) return;
    try {
      const { error } = await supabase.from('sms_device_status').delete().eq('id', id);
      if (error) throw error;
      toast.success('تم حذف الجهاز');
      await load();
    } catch (err: any) {
      toast.error(err?.message || 'فشل حذف الجهاز');
    }
  };

  const isRecent = (date: string) => {
    const diff = Date.now() - new Date(date).getTime();
    return diff < 120_000;
  };

  const pendingForDevice = (deviceId: string) =>
    tasks.filter((t) => t.device_id === deviceId && ['pending', 'assigned', 'in_progress'].includes(t.task_status));

  return (
    <AdminLayout>
      <div className="px-4 md:px-6 py-6 space-y-6">
        <div className="flex items-center gap-3 flex-wrap">
          <div className="space-y-0.5 flex-1 min-w-0">
            <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
              <Smartphone className="w-5 h-5 text-primary" />
              أجهزة SMS
            </h1>
            <p className="text-sm text-muted-foreground">متابعة حالة اتصال تطبيقات Android SMS Reader والمهام المسندة</p>
          </div>
          <Button variant="outline" size="sm" className="gap-1 shrink-0" onClick={load} disabled={loading}>
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} /> تحديث
          </Button>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">قائمة الأجهزة</CardTitle>
            <CardDescription>
              الجهاز يعتبر "متصل" لو بعت إشارة خلال آخر دقيقتين. الصفحة تتحديث كل 30 ثانية.
            </CardDescription>
          </CardHeader>
          <CardContent className="p-0">
            {devices.length === 0 ? (
              <div className="p-8 text-center space-y-3">
                <Smartphone className="w-10 h-10 text-muted-foreground mx-auto opacity-50" />
                <p className="text-sm text-muted-foreground">
                  لا توجد أجهزة مسجلة. افتح تطبيق Android واضغط حفظ الإعدادات.
                </p>
              </div>
            ) : (
              <div className="divide-y divide-border">
                {devices.map((device) => {
                  const pending = pendingForDevice(device.device_id);
                  return (
                    <div key={device.id} className="p-4 space-y-3">
                      <div className="flex items-start justify-between gap-4">
                        <div className="min-w-0 space-y-1.5 flex-1">
                          <div className="flex items-center gap-2 flex-wrap">
                            {device.status === 'online' && isRecent(device.last_heartbeat_at) ? (
                              <Badge className="gap-1 bg-green-500/10 text-green-500 hover:bg-green-500/20">
                                <Wifi className="w-3 h-3" /> متصل
                              </Badge>
                            ) : (
                              <Badge variant="destructive" className="gap-1">
                                <WifiOff className="w-3 h-3" /> غير متصل
                              </Badge>
                            )}
                            {device.app_version && (
                              <span className="text-xs text-muted-foreground">v{device.app_version}</span>
                            )}
                            {pending.length > 0 && (
                              <Badge variant="secondary" className="gap-1">
                                {pending.length} مهام نشطة
                              </Badge>
                            )}
                          </div>
                          <p className="text-sm font-medium truncate">
                            {device.device_model || 'جهاز غير معروف'} {device.device_name ? `(${device.device_name})` : ''}
                          </p>
                          <div className="flex items-center gap-3 text-xs text-muted-foreground flex-wrap">
                            <span className="flex items-center gap-1"><Hash className="w-3 h-3" /> {device.device_id}</span>
                            {device.android_version && (
                              <span className="flex items-center gap-1"><Cpu className="w-3 h-3" /> Android {device.android_version}</span>
                            )}
                            {device.phone_number && (
                              <span className="flex items-center gap-1"><Phone className="w-3 h-3" /> {device.phone_number}</span>
                            )}
                          </div>
                          <div className="flex items-center gap-3 text-xs text-muted-foreground">
                            <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> آخر نبضة: {new Date(device.last_heartbeat_at).toLocaleString('ar-SA')}</span>
                            {device.last_webhook_at && (
                              <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> آخر SMS: {new Date(device.last_webhook_at).toLocaleString('ar-SA')}</span>
                            )}
                          </div>
                        </div>
                        <Button variant="ghost" size="icon" className="shrink-0 text-destructive hover:text-destructive" onClick={() => deleteDevice(device.id)}>
                          <Trash2 className="w-4 h-4" />
                        </Button>
                      </div>

                      {pending.length > 0 && (
                        <>
                          <Separator />
                          <div className="space-y-2">
                            <p className="text-xs font-medium text-muted-foreground">مهام قيد الفحص:</p>
                            {pending.map((task) => (
                              <div key={task.id} className="p-2 rounded-md bg-muted/30 text-xs">
                                <div className="flex items-center justify-between">
                                  <span className="font-medium">طلب {task.request_id.slice(0, 8)}</span>
                                  <Badge variant="outline" className="text-[10px]">
                                    {task.task_status === 'in_progress' ? 'جاري الفحص' : 'في الانتظار'}
                                  </Badge>
                                </div>
                                <p className="text-muted-foreground mt-1">
                                  المبلغ: {task.amount_requested?.toFixed(2) ?? '—'} جنيه · من: {task.sender_phone_requested ?? '—'}
                                </p>
                              </div>
                            ))}
                          </div>
                        </>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  );
}
