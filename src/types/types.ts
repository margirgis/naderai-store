export type UserRole = 'user' | 'admin';

export interface Profile {
  id: string;
  email: string | null;
  phone: string | null;
  role: UserRole;
  wallet_balance: number;
  status: 'active' | 'suspended' | 'banned';
  created_at: string;
  updated_at: string;
}

export type OrderStatus =
  | 'creating' | 'queued' | 'processing'
  | 'success' | 'partial' | 'failed'
  | 'cancelled' | 'rejected';

export interface Order {
  id: string;
  customer_id: string;
  service_id: string;
  provider_service_code: string;
  provider_task_id: string | null;
  provider_request_id: string | null;
  reference: string;
  quantity: number | null;
  customer_total: number;
  provider_cost: number;
  status: OrderStatus;
  result_data: Record<string, unknown> | null;
  result_available: boolean;
  // New fields for customer store
  offer_link: string | null;
  two_fa_link: string | null;
  activation_data: Record<string, unknown> | null;
  safe_error_code: string | null;
  safe_error_message: string | null;
  idempotency_key: string | null;
  poll_count: number;
  last_polled_at: string | null;
  webhook_received_at: string | null;
  provider_raw_response: Record<string, unknown> | null;
  completed_at: string | null;
  created_at: string;
  updated_at: string | null;
  // joined
  provider_services?: { name: string; display_name_ar?: string | null; input_type: string | null; provider_code?: string };
  profiles?: { email: string | null };
}

export type WalletTxType = 'credit' | 'debit' | 'hold' | 'release';

export type TopupRequestStatus = 'pending' | 'approved' | 'rejected';

export interface WalletTopupRequest {
  id: string;
  customer_id: string;
  amount: number;
  status: TopupRequestStatus;
  payment_method: string;
  sender_phone: string | null;
  transaction_reference: string | null;
  notes: string | null;
  created_at: string;
  updated_at: string;
  processed_at: string | null;
  processed_by: string | null;
}

export interface WalletTransaction {
  id: string;
  customer_id: string;
  type: WalletTxType;
  amount: number;
  balance_after: number;
  reason: string;
  order_id: string | null;
  reference: string | null;
  created_at: string;
  orders?: { reference: string } | null;
}

export interface ProviderConfig {
  id: string;
  environment: string;
  base_url: string;
  key_status: 'valid' | 'invalid' | 'unknown';
  last_health_check_at: string | null;
  last_health_check_success: boolean | null;
  // Balance fields
  balance_credit: number | null;
  balance_currency: string | null;
  balance_synced_at: string | null;
  last_error_code: string | null;
  last_error_message: string | null;
  last_response_time_ms: number | null;
  // Live stats fields
  services_count: number | null;
  services_available: number | null;
  services_maintenance: number | null;
  orders_total: number | null;
  orders_active: number | null;
  stats_synced_at: string | null;
  last_request_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface LiveTestResult {
  overall: 'PASS' | 'FAIL';
  environment: string;
  base_url: string;
  key_configured: boolean;
  masked_key: string | null;
  tested_at: string;
  avg_response_ms: number;
  steps: Array<{
    name: string;
    result: 'PASS' | 'FAIL';
    http_status: number;
    response_time_ms: number;
    request_id: string | null;
    error: string | null;
  }>;
  live_data: {
    balance: { credit: number | null; currency: string } | null;
    services_count: number;
    services_available: number | null;
    services_maintenance: number | null;
    orders_total: number | null;
    orders_active: number | null;
  };
  last_request_id: string | null;
  last_error: string | null;
}

export interface ProviderService {
  id: string;
  provider_code: string;
  name: string;
  display_name_ar: string | null;
  display_name_en: string | null;
  status: string;
  input_type: string | null;
  provider_credit_price: number | null;
  provider_idr_price: number | null;
  provider_usd_price: number | null;
  discount_percent: number | null;
  final_credit_price: number | null;
  max_items_per_request: number;
  returns: boolean;
  is_enabled: boolean;
  customer_price: number | null;
  store_enabled: boolean;
  description_ar: string | null;
  last_synced_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface Notification {
  id: string;
  user_id: string;
  type: 'order_created' | 'order_updated' | 'order_success' | 'order_failed' | 'offer_link_ready' | 'wallet_topup' | 'wallet_debit';
  title: string;
  body: string;
  order_id: string | null;
  is_read: boolean;
  created_at: string;
}

export interface ProviderLog {
  id: string;
  operation: string;
  success: boolean;
  http_status: number | null;
  provider_request_id: string | null;
  response_time_ms: number | null;
  error_code: string | null;
  error_message: string | null;
  created_at: string;
}

// Edge function response shapes
export interface HealthCheckResult {
  environment: string;
  base_url: string;
  key_configured: boolean;
  provider_reachable: boolean;
  auth_valid: boolean;
  success: boolean;
  response_time_ms: number;
  provider_request_id: string | null;
  error: string | null;
  checked_at: string;
}

export interface SyncServicesResult {
  success: boolean;
  synced: number;
  errors: number;
  total_in_db: number;
  available: number;
  maintenance: number;
  synced_at: string;
  provider_request_id: string | null;
  error?: string;
  error_code?: string;
}

export interface BalanceResult {
  success: boolean;
  credit: number | null;
  credit_equivalent: number | null;
  total_topup: number | null;
  currency: string | null;
  synced_at: string;
  provider_request_id: string | null;
  response_time_ms: number;
  error?: string;
  error_code?: string;
}

export interface TestResult {
  result: 'PASS' | 'FAIL';
  http_status: number;
  response_time_ms: number;
  provider_request_id: string | null;
  error: string | null;
}

export interface TestConnectionResult {
  environment: string;
  key_configured: boolean;
  overall: 'PASS' | 'FAIL';
  masked_key?: string;
  avg_response_ms?: number;
  services_count?: number;
  balance?: { credit?: number; currency?: string };
  tests: {
    authentication: TestResult;
    get_services: TestResult;
    get_balance: TestResult;
  };
  tested_at: string;
}
