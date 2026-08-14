import React, { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  LayoutDashboard,
  Plug,
  Menu,
  LogOut,
  ChevronRight,
  Shield,
  Users,
  ClipboardList,
  Wallet,
  Settings,
  Banknote,
  Github,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent } from '@/components/ui/sheet';
import { cn } from '@/lib/utils';
import { useAuth } from '@/contexts/AuthContext';

const navItems = [
  { label: 'لوحة التحكم', path: '/', icon: LayoutDashboard },
  { label: 'واجهة المزود (API)', path: '/admin/provider', icon: Plug },
  { label: 'العملاء', path: '/admin/customers', icon: Users },
  { label: 'الطلبات', path: '/admin/orders', icon: ClipboardList },
  { label: 'المحافظ', path: '/admin/wallet', icon: Wallet },
  { label: 'طلبات الشحن', path: '/admin/topup-requests', icon: Banknote },
  { label: 'الخدمات', path: '/admin/services', icon: Settings },
  { label: 'GitHub', path: '/admin/github', icon: Github },
];

function NavContent({ onClose }: { onClose?: () => void }) {
  const location = useLocation();
  const { profile, signOut } = useAuth();

  return (
    <div className="flex flex-col h-full">
      {/* Brand */}
      <div className="flex items-center gap-2 px-4 py-5 border-b border-sidebar-border">
        <div className="w-7 h-7 rounded bg-primary flex items-center justify-center shrink-0">
          <Shield className="w-4 h-4 text-primary-foreground" />
        </div>
        <div className="min-w-0">
          <p className="text-sm font-semibold text-sidebar-accent-foreground truncate">Nader AI</p>
          <p className="text-xs text-muted-foreground truncate">لوحة الإدارة · Live</p>
        </div>
      </div>

      {/* Nav items */}
      <nav className="flex-1 px-2 py-4 space-y-0.5">
        {navItems.map((item) => {
          const active = location.pathname === item.path ||
            (item.path !== '/' && location.pathname.startsWith(item.path));
          return (
            <Link
              key={item.path}
              to={item.path}
              onClick={onClose}
              className={cn(
                'flex items-center gap-3 px-3 py-2.5 rounded text-sm font-medium transition-colors',
                active
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground border border-primary/20'
                  : 'text-sidebar-foreground hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground'
              )}
            >
              <item.icon className={cn('w-4 h-4 shrink-0', active ? 'text-primary' : '')} />
              <span className="truncate">{item.label}</span>
              {active && <ChevronRight className="w-3 h-3 me-auto text-primary shrink-0 rotate-180" />}
            </Link>
          );
        })}
      </nav>

      {/* User */}
      <div className="px-3 py-4 border-t border-sidebar-border">
        <div className="flex items-center gap-2 min-w-0 mb-3">
          <div className="w-7 h-7 rounded-full bg-muted flex items-center justify-center shrink-0 text-xs font-bold text-muted-foreground uppercase">
            {profile?.email?.[0] ?? 'A'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs font-medium text-sidebar-accent-foreground truncate">{profile?.email ?? 'admin'}</p>
            <p className="text-xs text-muted-foreground capitalize">{profile?.role === 'admin' ? 'مسؤول' : profile?.role ?? 'مسؤول'}</p>
          </div>
        </div>
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start gap-2 text-muted-foreground hover:text-foreground"
          onClick={signOut}
        >
          <LogOut className="w-4 h-4" />
          تسجيل الخروج
        </Button>
      </div>
    </div>
  );
}

export function AdminLayout({ children }: { children: React.ReactNode }) {
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <div className="flex min-h-screen w-full bg-background">
      {/* الشريط الجانبي — سطح المكتب */}
      <aside className="hidden md:flex flex-col w-56 shrink-0 bg-sidebar border-l border-sidebar-border">
        <NavContent />
      </aside>

      {/* الشريط الجانبي — الجوال */}
      <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
        <SheetContent side="right" className="w-56 p-0 bg-sidebar border-l border-sidebar-border">
          <NavContent onClose={() => setMobileOpen(false)} />
        </SheetContent>
      </Sheet>

      {/* Main content */}
      <div className="flex-1 min-w-0 flex flex-col">
        {/* Mobile top bar */}
        <header className="md:hidden flex items-center gap-3 px-4 py-3 border-b border-border">
          <Button
            variant="ghost"
            size="icon"
            className="shrink-0"
            onClick={() => setMobileOpen(true)}
          >
            <Menu className="w-5 h-5" />
          </Button>
          <div className="flex items-center gap-2 min-w-0">
            <Shield className="w-4 h-4 text-primary shrink-0" />
            <span className="text-sm font-semibold truncate">Nader AI — لوحة الإدارة</span>
          </div>
        </header>

        <main className="flex-1 overflow-y-auto">
          {children}
        </main>
      </div>
    </div>
  );
}
