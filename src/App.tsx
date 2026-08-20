import React, { Suspense } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import IntersectObserver from '@/components/common/IntersectObserver';
import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AuthProvider } from '@/contexts/AuthContext';
import { routes } from './routes';
import { useAuth } from '@/contexts/AuthContext';

const Spinner = () => (
  <div className="min-h-screen bg-[hsl(222,47%,4%)] flex flex-col items-center justify-center gap-4">
    <div className="w-8 h-8 border-4 border-cyan-500 border-t-transparent rounded-full animate-spin" />
    <p className="text-slate-400 text-sm">جاري التحميل…</p>
  </div>
);

/** Admin-only guard */
function AdminRoute({ children }: { children: React.ReactNode }) {
  const { session, loading, isAdmin } = useAuth();
  if (loading) return <Spinner />;
  if (!session) return <Navigate to="/login" replace />;
  if (!isAdmin) return <Navigate to="/store" replace />;
  return <>{children}</>;
}

/** Any authenticated user guard */
function CustomerRoute({ children }: { children: React.ReactNode }) {
  const { session, loading } = useAuth();
  if (loading) return <Spinner />;
  if (!session) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function AppRoutes() {
  const { session, isAdmin } = useAuth();
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
          // legacy fallback: admin only
          element = <AdminRoute>{route.element}</AdminRoute>;
        }
        return <Route key={index} path={route.path} element={element} />;
      })}
      {/* Fallback redirect */}
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

const PageLoader = () => (
  <div className="min-h-screen bg-[hsl(222,47%,4%)] flex flex-col items-center justify-center gap-4">
    <div className="w-8 h-8 border-4 border-cyan-500 border-t-transparent rounded-full animate-spin" />
    <p className="text-slate-400 text-sm">جاري تحميل الصفحة…</p>
  </div>
);

const App: React.FC = () => {
  return (
    <Router>
      <TooltipProvider>
        <AuthProvider>
          <IntersectObserver />
          <Suspense fallback={<PageLoader />}>
            <AppRoutes />
          </Suspense>
          <Toaster richColors position="top-right" />
        </AuthProvider>
      </TooltipProvider>
    </Router>
  );
};

export default App;
