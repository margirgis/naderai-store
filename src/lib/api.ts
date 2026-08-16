import { supabase } from '@/db/supabase';
import type {
  ProviderService,
  ProviderLog,
  ProviderConfig,
  HealthCheckResult,
  SyncServicesResult,
  BalanceResult,
  TestConnectionResult,
  LiveTestResult,
} from '@/types/types';

// ─── Provider Config ────────────────────────────────────────────────────────

export async function getProviderConfig(): Promise<ProviderConfig | null> {
  const { data } = await supabase
    .from('provider_config')
    .select('*')
    .order('created_at', { ascending: true })
    .limit(1)
    .maybeSingle();
  return data ?? null;
}

// ─── Provider Services ───────────────────────────────────────────────────────

export async function getProviderServices(page = 1, pageSize = 50): Promise<{ data: ProviderService[]; count: number }> {
  const from = (page - 1) * pageSize;
  const to = from + pageSize - 1;
  const { data, count, error } = await supabase
    .from('provider_services')
    .select('*', { count: 'exact' })
    .order('name', { ascending: true })
    .range(from, to);
  if (error) throw error;
  return { data: Array.isArray(data) ? data : [], count: count ?? 0 };
}

export async function getServicesStats(): Promise<{ total: number; available: number; maintenance: number }> {
  const { count: total } = await supabase
    .from('provider_services')
    .select('*', { count: 'exact', head: true });

  const { count: available } = await supabase
    .from('provider_services')
    .select('*', { count: 'exact', head: true })
    .eq('status', 'active');

  const { count: maintenance } = await supabase
    .from('provider_services')
    .select('*', { count: 'exact', head: true })
    .eq('status', 'maintenance');

  return { total: total ?? 0, available: available ?? 0, maintenance: maintenance ?? 0 };
}

// ─── Provider Logs ───────────────────────────────────────────────────────────

export async function getProviderLogs(page = 1, pageSize = 30): Promise<{ data: ProviderLog[]; count: number }> {
  const from = (page - 1) * pageSize;
  const to = from + pageSize - 1;
  const { data, count, error } = await supabase
    .from('provider_logs')
    .select('*', { count: 'exact' })
    .order('created_at', { ascending: false })
    .range(from, to);
  if (error) throw error;
  return { data: Array.isArray(data) ? data : [], count: count ?? 0 };
}

// ─── Edge Function Calls ─────────────────────────────────────────────────────

async function invokeEdgeFunction<T>(name: string, options?: { method?: string; body?: Record<string, unknown> }): Promise<T> {
  const { data, error } = await supabase.functions.invoke<T>(name, {
    method: (options?.method ?? 'POST') as 'GET' | 'POST',
    body: options?.body,
  });
  if (error) {
    const msg = await error?.context?.text?.().catch(() => error?.message ?? 'Unknown error');
    throw new Error(msg);
  }
  return data as T;
}

export async function runHealthCheck(): Promise<HealthCheckResult> {
  return invokeEdgeFunction<HealthCheckResult>('provider-health', { method: 'POST' });
}

export async function syncServices(): Promise<SyncServicesResult> {
  return invokeEdgeFunction<SyncServicesResult>('provider-sync-services', { method: 'POST' });
}

export async function refreshBalance(): Promise<BalanceResult> {
  return invokeEdgeFunction<BalanceResult>('provider-balance', { method: 'POST' });
}

export async function testConnection(): Promise<TestConnectionResult> {
  return invokeEdgeFunction<TestConnectionResult>('provider-test-connection', { method: 'POST' });
}

export async function runLiveTest(): Promise<LiveTestResult> {
  return invokeEdgeFunction<LiveTestResult>('provider-live-test', { method: 'POST' });
}

export interface UpdateApiKeyResult {
  success: boolean;
  key_updated: boolean;
  masked_key?: string;
  message?: string;
  stage?: string;
  error?: string;
  tests?: {
    authentication: { result: string; response_time_ms: number };
    get_services: { result: string; response_time_ms: number };
  };
}

export async function updateApiKey(newKey: string): Promise<UpdateApiKeyResult> {
  return invokeEdgeFunction<UpdateApiKeyResult>('provider-update-key', {
    method: 'POST',
    body: { api_key: newKey },
  });
}
