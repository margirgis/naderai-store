/**
 * provider-live-test — Admin-only live connection test.
 *
 * Runs 4 real backend requests in sequence:
 *   1. GET /balance       — authentication + balance
 *   2. GET /services      — service list
 *   3. GET /stats/orders  — order statistics
 *   4. GET /stats/services — service statistics
 *
 * SECURITY:
 *   - Admin-only (requireAdmin)
 *   - Never logs or returns API key
 *   - Never creates any order
 *   - Saves results to provider_config + provider_logs
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCall, getEnvironment, getBaseUrl, hasApiKey, getMaskedKeyPrefix } from '../_shared/provider-client.ts';

interface TestStep {
  name: string;
  result: 'PASS' | 'FAIL';
  http_status: number;
  response_time_ms: number;
  request_id: string | null;
  error: string | null;
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  try { await requireAdmin(req); } catch (err) {
    return errorResponse(String(err), 403);
  }

  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  const environment = getEnvironment();
  const baseUrl = getBaseUrl();
  const keyConfigured = hasApiKey();
  const maskedKey = getMaskedKeyPrefix();
  const testedAt = new Date().toISOString();
  const steps: TestStep[] = [];

  // ── 1. GET /balance ──────────────────────────────────────
  const balResult = await providerCall<{
    credit?: number | string;
    currency?: string;
    [k: string]: unknown;
  }>('balance', 'GET');

  steps.push({
    name: 'GET /balance',
    result: balResult.ok ? 'PASS' : 'FAIL',
    http_status: balResult.httpStatus,
    response_time_ms: balResult.responseTimeMs,
    request_id: balResult.requestId ?? null,
    error: balResult.ok ? null : (balResult.errorMessage ?? null),
  });

  await supabase.from('provider_logs').insert({
    operation: 'live_test_balance',
    success: balResult.ok,
    http_status: balResult.httpStatus,
    provider_request_id: balResult.requestId ?? null,
    response_time_ms: balResult.responseTimeMs,
    error_code: balResult.errorCode ?? null,
    error_message: balResult.errorMessage ?? null,
  });

  // ── 2. GET /services ─────────────────────────────────────
  const svcResult = await providerCall<unknown>('services', 'GET');

  // Parse services count flexibly
  let servicesCount = 0;
  if (Array.isArray(svcResult.data)) {
    servicesCount = svcResult.data.length;
  } else if (svcResult.data && typeof svcResult.data === 'object') {
    const d = svcResult.data as Record<string, unknown>;
    const nested = d['services'] ?? d['data'] ?? d['items'] ?? d['result'];
    if (Array.isArray(nested)) servicesCount = nested.length;
  }

  steps.push({
    name: 'GET /services',
    result: svcResult.ok ? 'PASS' : 'FAIL',
    http_status: svcResult.httpStatus,
    response_time_ms: svcResult.responseTimeMs,
    request_id: svcResult.requestId ?? null,
    error: svcResult.ok ? null : (svcResult.errorMessage ?? null),
  });

  await supabase.from('provider_logs').insert({
    operation: 'live_test_services',
    success: svcResult.ok,
    http_status: svcResult.httpStatus,
    provider_request_id: svcResult.requestId ?? null,
    response_time_ms: svcResult.responseTimeMs,
    error_code: svcResult.errorCode ?? null,
    error_message: svcResult.errorMessage ?? null,
    extra_data: { services_count: servicesCount },
  });

  // ── 3. GET /stats/orders ─────────────────────────────────
  const ordStatsResult = await providerCall<{
    total?: number; active?: number; success?: number;
    [k: string]: unknown;
  }>('stats/orders', 'GET');

  steps.push({
    name: 'GET /stats/orders',
    result: ordStatsResult.ok ? 'PASS' : 'FAIL',
    http_status: ordStatsResult.httpStatus,
    response_time_ms: ordStatsResult.responseTimeMs,
    request_id: ordStatsResult.requestId ?? null,
    error: ordStatsResult.ok ? null : (ordStatsResult.errorMessage ?? null),
  });

  await supabase.from('provider_logs').insert({
    operation: 'live_test_stats_orders',
    success: ordStatsResult.ok,
    http_status: ordStatsResult.httpStatus,
    provider_request_id: ordStatsResult.requestId ?? null,
    response_time_ms: ordStatsResult.responseTimeMs,
    error_code: ordStatsResult.errorCode ?? null,
    error_message: ordStatsResult.errorMessage ?? null,
  });

  // ── 4. GET /stats/services ───────────────────────────────
  const svcStatsResult = await providerCall<{
    available?: number; maintenance?: number;
    [k: string]: unknown;
  }>('stats/services', 'GET');

  steps.push({
    name: 'GET /stats/services',
    result: svcStatsResult.ok ? 'PASS' : 'FAIL',
    http_status: svcStatsResult.httpStatus,
    response_time_ms: svcStatsResult.responseTimeMs,
    request_id: svcStatsResult.requestId ?? null,
    error: svcStatsResult.ok ? null : (svcStatsResult.errorMessage ?? null),
  });

  await supabase.from('provider_logs').insert({
    operation: 'live_test_stats_services',
    success: svcStatsResult.ok,
    http_status: svcStatsResult.httpStatus,
    provider_request_id: svcStatsResult.requestId ?? null,
    response_time_ms: svcStatsResult.responseTimeMs,
    error_code: svcStatsResult.errorCode ?? null,
    error_message: svcStatsResult.errorMessage ?? null,
  });

  // ── Determine overall result ─────────────────────────────
  // Must pass at minimum: balance + services
  const authPass = balResult.ok;
  const servicesPass = svcResult.ok;
  const overall: 'PASS' | 'FAIL' = authPass && servicesPass ? 'PASS' : 'FAIL';

  // Extract live data
  const bd = balResult.data ?? {};
  const balCredit = bd.credit != null ? Number(bd.credit) : null;
  const balCurrency = typeof bd.currency === 'string' ? bd.currency : 'CREDIT';

  const svcAvail = svcStatsResult.ok
    ? Number(svcStatsResult.data?.available ?? 0)
    : null;
  const svcMaint = svcStatsResult.ok
    ? Number(svcStatsResult.data?.maintenance ?? 0)
    : null;

  const ordTotal = ordStatsResult.ok
    ? Number(ordStatsResult.data?.total ?? 0)
    : null;
  const ordActive = ordStatsResult.ok
    ? Number(ordStatsResult.data?.active ?? 0)
    : null;

  const avgResponseMs = Math.round(
    steps.reduce((s, t) => s + t.response_time_ms, 0) / steps.length
  );

  const lastRequestId = steps.find(s => s.request_id)?.request_id ?? null;
  const lastError = steps.find(s => s.error)?.error ?? null;

  // ── Persist results to provider_config ───────────────────
  await supabase.from('provider_config').update({
    environment,
    key_status: authPass ? 'valid' : 'invalid',
    last_health_check_at: testedAt,
    last_health_check_success: overall === 'PASS',
    balance_credit: balCredit,
    balance_currency: balCurrency,
    balance_synced_at: balResult.ok ? testedAt : undefined,
    services_count: servicesCount > 0 ? servicesCount : undefined,
    services_available: svcAvail ?? undefined,
    services_maintenance: svcMaint ?? undefined,
    orders_total: ordTotal ?? undefined,
    orders_active: ordActive ?? undefined,
    stats_synced_at: testedAt,
    last_request_id: lastRequestId ?? undefined,
    last_error_message: lastError ?? undefined,
    last_response_time_ms: avgResponseMs,
  }).neq('id', '00000000-0000-0000-0000-000000000000');

  // ── Response (NEVER include API key) ─────────────────────
  return jsonResponse({
    overall,
    environment,
    base_url: baseUrl,
    key_configured: keyConfigured,
    masked_key: maskedKey || null,
    tested_at: testedAt,
    avg_response_ms: avgResponseMs,
    steps,
    live_data: {
      balance: balResult.ok ? { credit: balCredit, currency: balCurrency } : null,
      services_count: servicesCount,
      services_available: svcAvail,
      services_maintenance: svcMaint,
      orders_total: ordTotal,
      orders_active: ordActive,
    },
    last_request_id: lastRequestId,
    last_error: lastError,
  });
});
