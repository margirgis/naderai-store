import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCall } from '../_shared/provider-client.ts';

interface BalanceData {
  credit?: number | string;
  credit_equivalent?: number | string;
  total_topup?: number | string;
  currency?: string;
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

  const result = await providerCall<BalanceData>('balance', 'GET');

  await supabase.from('provider_logs').insert({
    operation: 'refresh_balance',
    success: result.ok,
    http_status: result.httpStatus,
    provider_request_id: result.requestId ?? null,
    response_time_ms: result.responseTimeMs,
    error_code: result.errorCode ?? null,
    error_message: result.errorMessage ?? null,
  });

  if (!result.ok) {
    return jsonResponse({
      success: false,
      error: result.errorMessage,
      error_code: result.errorCode,
    }, 200);
  }

  const bd = result.data ?? {};

  // Persist balance into provider_config for dashboard display
  await supabase
    .from('provider_config')
    .update({
      balance_credit: bd.credit != null ? Number(bd.credit) : null,
      balance_currency: bd.currency ?? 'CREDIT',
      balance_synced_at: new Date().toISOString(),
      last_health_check_at: new Date().toISOString(),
      last_health_check_success: true,
      key_status: 'valid',
    })
    .neq('id', '00000000-0000-0000-0000-000000000000');

  return jsonResponse({
    success: true,
    credit: bd.credit != null ? Number(bd.credit) : null,
    credit_equivalent: bd.credit_equivalent != null ? Number(bd.credit_equivalent) : null,
    total_topup: bd.total_topup != null ? Number(bd.total_topup) : null,
    currency: bd.currency ?? null,
    synced_at: new Date().toISOString(),
    provider_request_id: result.requestId ?? null,
    response_time_ms: result.responseTimeMs,
  });
});
