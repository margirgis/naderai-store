import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import ProviderPanelPage from './pages/ProviderPanelPage';
// Customer pages
import RegisterPage from './pages/customer/RegisterPage';
import CustomerDashboardPage from './pages/customer/CustomerDashboardPage';
import ServiceCatalogPage from './pages/customer/ServiceCatalogPage';
import OrderFormPage from './pages/customer/OrderFormPage';
import MyOrdersPage from './pages/customer/MyOrdersPage';
import OrderDetailPage from './pages/customer/OrderDetailPage';
import ActivationLinkPage from './pages/customer/ActivationLinkPage';
import CustomerWalletPage from './pages/customer/CustomerWalletPage';
import CustomerTopupRequestPage from './pages/customer/CustomerTopupRequestPage';
import CustomerProfilePage from './pages/customer/CustomerProfilePage';
import PaymentOrderPage from './pages/customer/PaymentOrderPage';
import ActivePaymentPage from './pages/customer/ActivePaymentPage';
// Admin pages
import AdminCustomersPage from './pages/admin/AdminCustomersPage';
import AdminOrdersPage from './pages/admin/AdminOrdersPage';
import AdminOrderDetailPage from './pages/admin/AdminOrderDetailPage';
import AdminWalletPage from './pages/admin/AdminWalletPage';
import AdminTopupRequestsPage from './pages/admin/AdminTopupRequestsPage';
import AdminServicesPage from './pages/admin/AdminServicesPage';
import AdminGithubPage from './pages/admin/AdminGithubPage';
import AdminSmsDevicesPage from './pages/admin/AdminSmsDevicesPage';
import AdminPackagesPage from './pages/admin/AdminPackagesPage';
import AdminTestOrderPage from './pages/admin/AdminTestOrderPage';
import type { ReactNode } from 'react';

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
