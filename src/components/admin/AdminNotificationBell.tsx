import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, CheckCheck, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { supabase } from '@/db/supabase';
import { formatDistanceToNow } from 'date-fns';
import { ar } from 'date-fns/locale';

interface AdminNotification {
  id: string;
  title: string;
  message: string;
  event_type: string;
  device_id?: string;
  reference_id?: string;
  is_read: boolean;
  created_at: string;
}

const EVENT_ICON: Record<string, string> = {
  device_registered:    '📱',
  test_ping_success:    '🧪',
  test_responded:       '✅',
  order_dispatched:     '📋',
  scan_success:         '✅',
  scan_not_found:       '❓',
  scan_failure:         '❌',
  scan_amount_mismatch: '⚠️',
  scan_duplicate:       '🔁',
  info:                 '🔔',
};

/** تحديد مسار التفاصيل بناءً على نوع الحدث والمرجع */
function resolveNotifPath(n: AdminNotification): string | null {
  const { event_type, reference_id, device_id } = n;
  if (event_type === 'order_dispatched' || event_type === 'scan_success' ||
      event_type === 'scan_not_found' || event_type === 'scan_failure' ||
      event_type === 'scan_amount_mismatch' || event_type === 'scan_duplicate') {
    return reference_id ? `/orders/${reference_id}` : '/orders';
  }
  if (event_type === 'device_registered' || event_type === 'test_ping_success' || event_type === 'test_responded') {
    return device_id ? `/devices/${device_id}` : '/devices';
  }
  return null;
}

export function AdminNotificationBell() {
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<AdminNotification[]>([]);
  const channelRef = useRef<ReturnType<typeof supabase.channel> | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const unread = notifications.filter((n) => !n.is_read).length;

  useEffect(() => {
    const load = async () => {
      const { data } = await supabase
        .from('notifications')
        .select('*')
        .order('created_at', { ascending: false })
        .limit(50);
      setNotifications((data ?? []) as AdminNotification[]);
    };
    load();

    const channelName = `admin-notifications-bell-${crypto.randomUUID()}`;
    const channel = supabase
      .channel(channelName)
      .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'notifications' }, (payload) => {
        setNotifications((prev) => [payload.new as AdminNotification, ...prev].slice(0, 50));
      })
      .subscribe();
    channelRef.current = channel;

    return () => {
      supabase.removeChannel(channel).catch(() => {});
      channelRef.current = null;
    };
  }, []);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) setOpen(false);
    };
    if (open) document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const markRead = async (id: string) => {
    await supabase.from('notifications').update({ is_read: true }).eq('id', id);
    setNotifications((prev) => prev.map((n) => n.id === id ? { ...n, is_read: true } : n));
  };

  const markAllRead = async () => {
    await supabase.from('notifications').update({ is_read: true }).eq('is_read', false);
    setNotifications((prev) => prev.map((n) => ({ ...n, is_read: true })));
  };

  const dismiss = (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    setNotifications((prev) => prev.filter((n) => n.id !== id));
  };

  const handleClick = async (n: AdminNotification) => {
    await markRead(n.id);
    setOpen(false);
    const path = resolveNotifPath(n);
    if (path) navigate(path);
  };

  return (
    <div className="relative" ref={panelRef}>
      <Button
        variant="ghost"
        size="icon"
        className="relative"
        onClick={() => setOpen((o) => !o)}
        aria-label="الإشعارات"
      >
        <Bell className="w-5 h-5" />
        {unread > 0 && (
          <Badge className="absolute -top-1 -right-1 min-w-[18px] h-[18px] p-0 flex items-center justify-center text-[10px] bg-red-500 text-white border-0">
            {unread > 99 ? '99+' : unread}
          </Badge>
        )}
      </Button>

      {open && (
        <div className="absolute left-0 top-full mt-2 w-80 md:w-96 bg-card border border-border rounded-xl shadow-xl z-50 overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 border-b border-border">
            <span className="text-sm font-semibold">الإشعارات {unread > 0 && `(${unread} جديد)`}</span>
            {unread > 0 && (
              <Button variant="ghost" size="sm" className="gap-1 text-xs h-7" onClick={markAllRead}>
                <CheckCheck className="w-3 h-3" /> قراءة الكل
              </Button>
            )}
          </div>

          <div className="overflow-y-auto max-h-[400px]">
            {notifications.length === 0 ? (
              <p className="text-center text-sm text-muted-foreground py-8">لا توجد إشعارات</p>
            ) : (
              notifications.map((n) => {
                const hasLink = resolveNotifPath(n) !== null;
                return (
                  <div
                    key={n.id}
                    onClick={() => handleClick(n)}
                    className={[
                      'flex items-start gap-3 px-4 py-3 border-b border-border/50 last:border-0 transition-colors',
                      !n.is_read ? 'bg-primary/5' : 'hover:bg-muted/40',
                      hasLink ? 'cursor-pointer' : 'cursor-default',
                    ].join(' ')}
                  >
                    <span className="text-lg shrink-0 mt-0.5">
                      {EVENT_ICON[n.event_type] ?? '🔔'}
                    </span>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{n.title}</p>
                      <p className="text-xs text-muted-foreground line-clamp-2">{n.message}</p>
                      {hasLink && (
                        <p className="text-[10px] text-primary mt-0.5">اضغط لعرض التفاصيل ←</p>
                      )}
                      <p className="text-[10px] text-muted-foreground mt-0.5">
                        {formatDistanceToNow(new Date(n.created_at), { addSuffix: true, locale: ar })}
                      </p>
                    </div>
                    <button
                      className="shrink-0 text-muted-foreground hover:text-foreground mt-0.5"
                      onClick={(e) => dismiss(e, n.id)}
                      aria-label="إغلاق"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}

