import { lazy } from 'react';
import type { ReactNode } from 'react';

// ── Lazy imports — كل صفحة تُحمَّل عند الحاجة فقط ──────────────────
const LoginPage             = lazy(() => import('./pages/LoginPage'));
const DashboardPage         = lazy(() => import('./pages/DashboardPage'));
const ProviderPanelPage     = lazy(() => import('./pages/ProviderPanelPage'));
// Customer pages
const RegisterPage              = lazy(() => import('./pages/customer/RegisterPage'));
const CustomerDashboardPage     = lazy(() => import('./pages/customer/CustomerDashboardPage'));
const ServiceCatalogPage        = lazy(() => import('./pages/customer/ServiceCatalogPage'));
const OrderFormPage             = lazy(() => import('./pages/customer/OrderFormPage'));
const MyOrdersPage              = lazy(() => import('./pages/customer/MyOrdersPage'));
const OrderDetailPage           = lazy(() => import('./pages/customer/OrderDetailPage'));
const ActivationLinkPage        = lazy(() => import('./pages/customer/ActivationLinkPage'));
const CustomerWalletPage        = lazy(() => import('./pages/customer/CustomerWalletPage'));
const CustomerTopupRequestPage  = lazy(() => import('./pages/customer/CustomerTopupRequestPage'));
const CustomerProfilePage       = lazy(() => import('./pages/customer/CustomerProfilePage'));
const PaymentOrderPage          = lazy(() => import('./pages/customer/PaymentOrderPage'));
const ActivePaymentPage         = lazy(() => import('./pages/customer/ActivePaymentPage'));
// Admin pages
const AdminCustomersPage        = lazy(() => import('./pages/admin/AdminCustomersPage'));
const AdminOrdersPage           = lazy(() => import('./pages/admin/AdminOrdersPage'));
const AdminOrderDetailPage      = lazy(() => import('./pages/admin/AdminOrderDetailPage'));
const AdminWalletPage           = lazy(() => import('./pages/admin/AdminWalletPage'));
const AdminTopupRequestsPage    = lazy(() => import('./pages/admin/AdminTopupRequestsPage'));
const AdminServicesPage         = lazy(() => import('./pages/admin/AdminServicesPage'));
const AdminGithubPage           = lazy(() => import('./pages/admin/AdminGithubPage'));
const AdminSmsDevicesPage       = lazy(() => import('./pages/admin/AdminSmsDevicesPage'));
const AdminPackagesPage         = lazy(() => import('./pages/admin/AdminPackagesPage'));
const AdminTestOrderPage        = lazy(() => import('./pages/admin/AdminTestOrderPage'));

export interface RouteConfig {
  name: string;
  path: string;
  element: ReactNode;
  public?: boolean;
  /** 'admin' = admin only, 'customer' = any authenticated user, undefined = admin only (legacy) */
  access?: 'admin' | 'customer' | 'public';
}

export const routes: RouteConfig[] = [
  // ─── Public ────────────────────────────────────────────────
  { name: 'Login',    path: '/login',    element: <LoginPage />,    public: true, access: 'public' },
  { name: 'Register', path: '/register', element: <RegisterPage />, public: true, access: 'public' },

  // ─── Admin ─────────────────────────────────────────────────
  { name: 'Dashboard',        path: '/',                element: <DashboardPage />,        access: 'admin' },
  { name: 'Provider API',     path: '/admin/provider',  element: <ProviderPanelPage />,    access: 'admin' },
  { name: 'Admin Customers',  path: '/admin/customers', element: <AdminCustomersPage />,   access: 'admin' },
  { name: 'Admin Orders',     path: '/admin/orders',    element: <AdminOrdersPage />,      access: 'admin' },
  { name: 'Admin Order',      path: '/admin/orders/:orderId', element: <AdminOrderDetailPage />, access: 'admin' },
  { name: 'Admin Wallet',     path: '/admin/wallet',    element: <AdminWalletPage />,      access: 'admin' },
  { name: 'Admin Topup Requests', path: '/admin/topup-requests', element: <AdminTopupRequestsPage />, access: 'admin' },
  { name: 'Admin Services',   path: '/admin/services',  element: <AdminServicesPage />,    access: 'admin' },
  { name: 'GitHub',           path: '/admin/github',    element: <AdminGithubPage />,      access: 'admin' },
  { name: 'SMS Devices',      path: '/admin/sms-devices', element: <AdminSmsDevicesPage />, access: 'admin' },
  { name: 'Credit Packages',  path: '/admin/packages',    element: <AdminPackagesPage />,   access: 'admin' },
  { name: 'Test Order',       path: '/admin/test-order',  element: <AdminTestOrderPage />,  access: 'admin' },

  // ─── Customer Store ────────────────────────────────────────
  { name: 'Store Dashboard',  path: '/store',                   element: <CustomerDashboardPage />, access: 'customer' },
  { name: 'Store Services',   path: '/store/services',          element: <ServiceCatalogPage />,    access: 'customer' },
  { name: 'Store Order Form', path: '/store/order/:serviceId',  element: <OrderFormPage />,         access: 'customer' },
  { name: 'My Orders',        path: '/store/orders',            element: <MyOrdersPage />,          access: 'customer' },
  { name: 'Order Detail',     path: '/store/orders/:orderId',            element: <OrderDetailPage />,       access: 'customer' },
  { name: 'Activation Link', path: '/store/orders/:orderId/activation', element: <ActivationLinkPage />,    access: 'customer' },
  { name: 'My Wallet',        path: '/store/wallet',            element: <CustomerWalletPage />,    access: 'customer' },
  { name: 'Topup Request',    path: '/store/wallet/topup',      element: <CustomerTopupRequestPage />, access: 'customer' },
  { name: 'Payment Order',    path: '/store/wallet/payment/:orderId', element: <PaymentOrderPage />, access: 'customer' },
  { name: 'Active Payment',   path: '/store/wallet/payment-pending', element: <ActivePaymentPage />, access: 'customer' },
  { name: 'My Profile',       path: '/store/profile',           element: <CustomerProfilePage />,   access: 'customer' },
];
