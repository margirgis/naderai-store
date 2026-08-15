import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import {
  ShoppingBag, ClipboardList, Wallet, User, LogOut,
  Shield, Bell, Home, Menu, X,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Sheet, SheetContent } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { useAuth } from '@/contexts/AuthContext';
import { supabase } from '@/db/supabase';
import type { Notification } from '@/types/types';
import WhatsAppChatButton from '@/components/customer/WhatsAppChatButton';

/* ── Notification Bell ─────────────────────────────────────────────────── */
function NotificationBell() {
  const { profile } = useAuth();
  const [notifs, setNotifs] = useState<Notification[]>([]);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    if (!profile?.id) return;
    const { data } = await supabase
      .from('notifications')
      .select('*')
      .eq('user_id', profile.id)
      .order('created_at', { ascending: false })
      .limit(20);
    setNotifs((data ?? []) as Notification[]);
  }, [profile?.id]);

  useEffect(() => {
    if (!profile?.id) return;
    load();
    const ch = supabase
      .channel(`notifs:${profile.id}`)
      .on('postgres_changes', {
        event: 'INSERT', schema: 'public', table: 'notifications',
        filter: `user_id=eq.${profile.id}`,
      }, (payload) => {
        setNotifs(prev => [payload.new as Notification, ...prev].slice(0, 20));
      })
      .subscribe();
    return () => { supabase.removeChannel(ch); };
  }, [profile?.id, load]);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    if (open) document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const unread = notifs.filter(n => !n.is_read).length;

  const markRead = async (id: string) => {
    await supabase.from('notifications').update({ is_read: true }).eq('id', id);
    setNotifs(prev => prev.map(n => n.id === id ? { ...n, is_read: true } : n));
  };

  const markAllRead = async () => {
    if (!profile?.id) return;
    await supabase.from('notifications').update({ is_read: true })
      .eq('user_id', profile.id).eq('is_read', false);
    setNotifs(prev => prev.map(n => ({ ...n, is_read: true })));
  };

  const typeIcon: Record<string, string> = {
    order_success: '🎉', offer_link_ready: '🎁', order_failed: '⚠️',
    order_created: '📦', order_updated: '🔄',
    wallet_topup: '💰', wallet_debit: '📉',
    wallet_topup_request: '📢',
  };

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(o => !o)}
        className="relative p-2 rounded-full hover:bg-muted/60 transition-colors"
        aria-label="الإشعارات"
      >
        <Bell className="w-5 h-5 text-foreground" />
        {unread > 0 && (
          <span className="absolute -top-0.5 -left-0.5 w-4 h-4 rounded-full bg-destructive text-[10px] text-white font-bold flex items-center justify-center">
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute left-0 top-10 w-80 max-w-[calc(100vw-2rem)] bg-card border border-border rounded-xl shadow-xl z-50 overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 border-b border-border">
            <span className="text-sm font-semibold text-foreground">الإشعارات</span>
            <div className="flex items-center gap-2">
              {unread > 0 && (
                <button onClick={markAllRead} className="text-xs text-primary hover:underline">
                  قراءة الكل
                </button>
              )}
              <button onClick={() => setOpen(false)} className="text-muted-foreground hover:text-foreground">
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>
          <div className="max-h-80 overflow-y-auto">
            {notifs.length === 0 ? (
              <p className="text-sm text-muted-foreground text-center py-8">لا توجد إشعارات</p>
            ) : notifs.map(n => (
              <Link
                key={n.id}
                to={
                  (n.type === 'wallet_topup' || n.type === 'wallet_debit')
                    ? '/store/wallet'
                    : n.type === 'offer_link_ready' && n.order_id
                    ? `/store/orders/${n.order_id}/activation`
                    : n.order_id
                    ? `/store/orders/${n.order_id}`
                    : '/store/orders'
                }
                onClick={() => { markRead(n.id); setOpen(false); }}
                className={cn(
                  'flex items-start gap-3 px-4 py-3 border-b border-border/50 last:border-0 hover:bg-muted/30 transition-colors',
                  !n.is_read && 'bg-primary/5'
                )}
              >
                <span className="text-lg shrink-0 mt-0.5">{typeIcon[n.type] ?? '🔔'}</span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-foreground line-clamp-1">{n.title}</p>
                  <p className="text-xs text-muted-foreground line-clamp-2">{n.body}</p>
                  <p className="text-[10px] text-muted-foreground mt-1">
                    {new Date(n.created_at).toLocaleString('ar-SA')}
                  </p>
                </div>
                {!n.is_read && <span className="w-2 h-2 rounded-full bg-primary shrink-0 mt-1.5" />}
              </Link>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Nav items ─────────────────────────────────────────────────────────── */
const navItems = [
  { label: 'الرئيسية', path: '/store', icon: Home },
  { label: 'الخدمات', path: '/store/services', icon: ShoppingBag },
  { label: 'طلباتي', path: '/store/orders', icon: ClipboardList },
  { label: 'محفظتي', path: '/store/wallet', icon: Wallet },
  { label: 'حسابي', path: '/store/profile', icon: User },
];

/* ── Sidebar NavContent ────────────────────────────────────────────────── */
function NavContent({ onClose }: { onClose?: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const { signOut, profile } = useAuth();

  return (
    <div className="flex flex-col h-full bg-sidebar">
      <div className="p-4 border-b border-sidebar-border">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-primary flex items-center justify-center shrink-0 shadow-sm">
            <Shield className="w-4 h-4 text-primary-foreground" />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-bold text-sidebar-foreground truncate">Nader AI</p>
            <p className="text-xs text-muted-foreground truncate">{profile?.email ?? 'عميل'}</p>
          </div>
        </div>
      </div>

      <div className="px-4 py-3 border-b border-sidebar-border">
        <div className="flex items-center justify-between">
          <span className="text-xs text-muted-foreground flex items-center gap-1">
            <Wallet className="w-3 h-3" /> رصيدي
          </span>
          <span className="text-sm font-bold text-primary">
            {(profile?.wallet_balance ?? 0).toFixed(1)} Credit
          </span>
        </div>
      </div>

      <nav className="flex-1 px-2 py-3 space-y-0.5">
        {navItems.map(({ label, path, icon: Icon }) => {
          const active = location.pathname === path ||
            (path !== '/store' && location.pathname.startsWith(path));
          return (
            <Link key={path} to={path} onClick={onClose}
              className={cn(
                'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors',
                active
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground font-semibold'
                  : 'text-sidebar-foreground hover:bg-sidebar-accent/50'
              )}>
              <Icon className="w-4 h-4 shrink-0" />
              <span className="truncate">{label}</span>
              {active && <span className="w-1.5 h-1.5 rounded-full bg-primary ms-auto shrink-0" />}
            </Link>
          );
        })}
      </nav>

      <div className="p-3 border-t border-sidebar-border">
        <button onClick={async () => { await signOut(); navigate('/login'); }}
          className="flex items-center gap-2 text-xs text-muted-foreground hover:text-destructive transition-colors w-full px-3 py-2 rounded-lg hover:bg-destructive/10">
          <LogOut className="w-4 h-4" />
          تسجيل الخروج
        </button>
      </div>
    </div>
  );
}

/* ── CustomerLayout ────────────────────────────────────────────────────── */
interface CustomerLayoutProps { children: React.ReactNode }

export function CustomerLayout({ children }: CustomerLayoutProps) {
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();
  const { profile } = useAuth();

  useEffect(() => { setMobileOpen(false); }, [location.pathname]);

  return (
    <div className="customer-theme flex min-h-screen w-full bg-background" dir="rtl">
      {/* Desktop sidebar */}
      <aside className="hidden md:flex flex-col w-60 shrink-0 border-l border-sidebar-border shadow-sm">
        <NavContent />
      </aside>

      {/* Mobile sidebar */}
      <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
        <SheetContent side="right" className="w-60 p-0 bg-sidebar border-l border-sidebar-border">
          <NavContent onClose={() => setMobileOpen(false)} />
        </SheetContent>
      </Sheet>

      {/* Main */}
      <div className="flex-1 min-w-0 overflow-x-hidden flex flex-col">
        {/* Header */}
        <header className="sticky top-0 z-40 flex items-center justify-between px-4 py-3 border-b border-border bg-card/95 backdrop-blur-sm shrink-0 shadow-sm">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="icon" className="h-8 w-8 md:hidden" onClick={() => setMobileOpen(true)}>
              <Menu className="w-4 h-4" />
            </Button>
            <div className="flex items-center gap-1.5">
              <div className="w-7 h-7 rounded-lg bg-primary flex items-center justify-center shadow-sm">
                <Shield className="w-3.5 h-3.5 text-primary-foreground" />
              </div>
              <span className="text-sm font-bold text-foreground">Nader AI</span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <span className="hidden sm:flex items-center gap-1 text-xs bg-muted/60 px-2.5 py-1 rounded-full border border-border">
              <Wallet className="w-3 h-3 text-primary" />
              <span className="font-semibold text-primary">{(profile?.wallet_balance ?? 0).toFixed(1)}</span>
              <span className="text-muted-foreground">Credit</span>
            </span>
            <NotificationBell />
          </div>
        </header>

        <main className="flex-1 overflow-y-auto pb-20 md:pb-6">
          {children}
        </main>

        {/* Floating WhatsApp support */}
        <WhatsAppChatButton />

        {/* Mobile Bottom Navigation */}
        <nav className="md:hidden fixed bottom-0 inset-x-0 z-40 bg-card border-t border-border flex items-stretch" dir="rtl">
          {navItems.map(({ label, path, icon: Icon }) => {
            const active = location.pathname === path ||
              (path !== '/store' && location.pathname.startsWith(path));
            return (
              <Link key={path} to={path}
                className={cn(
                  'relative flex-1 flex flex-col items-center justify-center gap-0.5 py-2 min-h-[3.25rem] transition-colors',
                  active ? 'text-primary' : 'text-muted-foreground'
                )}
              >
                <Icon className="w-5 h-5 shrink-0" />
                <span className="text-[10px] font-medium truncate max-w-full px-0.5">{label}</span>
                {active && <span className="absolute bottom-0 inset-x-0 h-0.5 rounded-full bg-primary" />}
              </Link>
            );
          })}
        </nav>
      </div>
    </div>
  );
}
