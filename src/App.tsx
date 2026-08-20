import React, { Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import IntersectObserver from '@/components/common/IntersectObserver';
import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AuthProvider } from '@/contexts/AuthContext';
import { routes } from './routes';
import { useAuth } from '@/contexts/AuthContext';
import { Shield } from 'lucide-react';

/** شاشة تحميل موحدة ببراند Nader AI */
const SplashScreen = ({ message = 'جاري التحميل…' }: { message?: string }) => (
  <div className="min-h-screen bg-[hsl(222,47%,4%)] flex flex-col items-center justify-center gap-5">
    <div className="flex items-center gap-3 mb-2">
      <div className="w-10 h-10 rounded-lg bg-primary flex items-center justify-center shadow-lg shadow-primary/30">
        <Shield className="w-6 h-6 text-primary-foreground" />
      </div>
      <span className="text-lg font-bold text-white tracking-wide">Nader AI</span>
    </div>
    <div className="w-8 h-8 border-[3px] border-cyan-500 border-t-transparent rounded-full animate-spin" />
    <p className="text-slate-400 text-sm">{message}</p>
  </div>
);

/** Admin-only guard */
function AdminRoute({ children }: { children: React.ReactNode }) {
  const { session, loading, isAdmin } = useAuth();
  if (loading) return <SplashScreen />;
  if (!session) return <Navigate to="/login" replace />;
  if (!isAdmin) return <Navigate to="/store" replace />;
  return <>{children}</>;
}

/** Any authenticated user guard */
function CustomerRoute({ children }: { children: React.ReactNode }) {
  const { session, loading } = useAuth();
  if (loading) return <SplashScreen />;
  if (!session) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function AppRoutes() {
  const { session, loading, isAdmin } = useAuth();

  // لا تُعيد توجيهاً حتى يكتمل تحميل profile — يمنع إرسال الأدمن لـ /store
  if (loading) return <SplashScreen />;

  return (
    <Routes>
      {routes.map((route, index) => {
        let element: React.ReactNode;
        if (route.access === 'public') {
          element = route.element;
        } else if (route.access === 'admin') {
          element = <AdminRoute>{route.element}</AdminRoute>;
        } else if (route.access === 'customer') {
          element = <CustomerRoute>{route.element}</CustomerRoute>;
        } else {
          element = <AdminRoute>{route.element}</AdminRoute>;
        }
        return <Route key={index} path={route.path} element={element} />;
      })}
      {/* Fallback redirect — ينتفذ بعد تحميل profile فنعرف الدور الحقيقي */}
      <Route
        path="*"
        element={
          <Navigate
            to={session ? (isAdmin ? '/' : '/store') : '/login'}
            replace
          />
        }
      />
    </Routes>
  );
}

const App: React.FC = () => {
  return (
    <Router>
      <TooltipProvider>
        <AuthProvider>
          <IntersectObserver />
          <Suspense fallback={<SplashScreen message="جاري تحميل الصفحة…" />}>
            <AppRoutes />
          </Suspense>
          <Toaster richColors position="top-right" />
        </AuthProvider>
      </TooltipProvider>
    </Router>
  );
};

export default App;
