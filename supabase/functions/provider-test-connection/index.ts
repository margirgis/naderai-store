import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCall, getEnvironment, getBaseUrl, hasApiKey } from '../_shared/provider-client.ts';

// SECURITY: return masked key prefix only — first 12 chars + redaction
function getMaskedKeyPrefix(): string {
  const k = Deno.env.get('PROVIDER_API_KEY') ?? '';
  if (!k) return '';
  return `${k.slice(0, 12)}••••••••••••••••••`;
}

interface BalanceData {
  credit?: number | string;
  credit_equivalent?: number | string;
  currency?: string;
  [key: string]: unknown;
}

interface ServiceItem {
  [key: string]: unknown;
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  try {
    await requireAdmin(req);
  } catch (err) {
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
  // SECURITY: only return masked prefix — never full key
  const maskedKey = getMaskedKeyPrefix();

  // ── Test 1: Authentication via GET /balance ──────────────────
  const authResult = await providerCall<BalanceData>('balance', 'GET');
  const authPass = authResult.ok;

  await supabase.from('provider_logs').insert({
    operation: 'test_connection_auth',
    success: authPass,
    http_status: authResult.httpStatus,
    provider_request_id: authResult.requestId ?? null,
    response_time_ms: authResult.responseTimeMs,
    error_code: authResult.errorCode ?? null,
    error_message: authResult.errorMessage ?? null,
  });

  // ── Test 2: GET /services ────────────────────────────────────
  const servicesResult = await providerCall<ServiceItem[]>('services', 'GET');
  const servicesPass = servicesResult.ok;

  await supabase.from('provider_logs').insert({
    operation: 'test_connection_services',
    success: servicesPass,
    http_status: servicesResult.httpStatus,
    provider_request_id: servicesResult.requestId ?? null,
    response_time_ms: servicesResult.responseTimeMs,
    error_code: servicesResult.errorCode ?? null,
    error_message: servicesResult.errorMessage ?? null,
  });

  const allPass = authPass && servicesPass;
  const avgResponseMs = Math.round(
    (authResult.responseTimeMs + servicesResult.responseTimeMs) / 2
  );

  // Balance details (safe fields only)
  const bd = authResult.data ?? {} as BalanceData;
  const balanceInfo = authPass
    ? {
        credit: bd.credit != null ? Number(bd.credit) : null,
        currency: bd.currency ?? null,
      }
    : null;

  // Services count
  const servicesArr = Array.isArray(servicesResult.data) ? servicesResult.data : [];
  const servicesCount = servicesArr.length;

  // Persist last check summary in provider_config
  await supabase
    .from('provider_config')
    .update({
      key_status: authPass ? 'valid' : (authResult.errorCode === 'AUTH_INVALID' ? 'invalid' : 'unknown'),
      last_health_check_at: new Date().toISOString(),
      last_health_check_success: allPass,
    })
    .neq('id', '00000000-0000-0000-0000-000000000000');

  return jsonResponse({
    environment,
    base_url: baseUrl,
    key_configured: keyConfigured,
    masked_key: maskedKey,           // SECURITY: prefix only, never full key
    overall: allPass ? 'PASS' : 'FAIL',
    connected: allPass,
    tests: {
      authentication: {
        result: authPass ? 'PASS' : 'FAIL',
        http_status: authResult.httpStatus,
        response_time_ms: authResult.responseTimeMs,
        error: authPass ? null : (authResult.errorMessage ?? null),
      },
      get_services: {
        result: servicesPass ? 'PASS' : 'FAIL',
        http_status: servicesResult.httpStatus,
        response_time_ms: servicesResult.responseTimeMs,
        error: servicesPass ? null : (servicesResult.errorMessage ?? null),
        count: servicesPass ? servicesCount : null,
      },
      get_balance: {
        result: authPass ? 'PASS' : 'FAIL',
        http_status: authResult.httpStatus,
        response_time_ms: authResult.responseTimeMs,
        error: authPass ? null : (authResult.errorMessage ?? null),
      },
    },
    balance: balanceInfo,
    services_count: servicesPass ? servicesCount : null,
    avg_response_ms: avgResponseMs,
    tested_at: new Date().toISOString(),
  });
});
