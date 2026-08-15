import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCall, getEnvironment, getBaseUrl, hasApiKey } from '../_shared/provider-client.ts';

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

  // Perform health check by calling GET /services (lightweight auth test)
  const healthStart = Date.now();
  const result = await providerCall('services', 'GET');
  const responseTimeMs = result.responseTimeMs;

  const isReachable = result.httpStatus > 0 || result.errorCode !== 'NETWORK_ERROR';
  const authValid = result.ok || (result.httpStatus !== 401 && result.httpStatus !== 403);
  const success = result.ok;

  // Log the health check
  await supabase.from('provider_logs').insert({
    operation: 'health_check',
    success,
    http_status: result.httpStatus,
    provider_request_id: result.requestId ?? null,
    response_time_ms: responseTimeMs,
    error_code: result.errorCode ?? null,
    error_message: result.errorMessage ?? null,
  });

  // Update provider_config
  await supabase
    .from('provider_config')
    .update({
      key_status: authValid ? 'valid' : 'invalid',
      last_health_check_at: new Date().toISOString(),
      last_health_check_success: success,
    })
    .neq('id', '00000000-0000-0000-0000-000000000000'); // update all rows

  return jsonResponse({
    environment,
    base_url: baseUrl,
    key_configured: keyConfigured,
    provider_reachable: isReachable,
    auth_valid: authValid,
    success,
    response_time_ms: responseTimeMs,
    provider_request_id: result.requestId ?? null,
    error: success ? null : result.errorMessage,
    checked_at: new Date().toISOString(),
  });
});
