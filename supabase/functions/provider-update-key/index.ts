/**
 * provider-update-key — Admin-only endpoint to update the Provider API key.
 *
 * Security flow:
 *  1. Validate caller is admin
 *  2. Accept new candidate key in request body
 *  3. Test candidate key against provider API (GET /balance + GET /services)
 *  4. Only if tests pass: store the new key via Supabase secrets upsert
 *  5. Return success/failure WITHOUT revealing the key in any response
 *
 * SECURITY: The candidate key is NEVER logged, NEVER returned in response.
 * SECURITY: The old key is kept active until new key passes validation.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCallWithKey, getMaskedKeyPrefix } from '../_shared/provider-client.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // ── Auth guard ────────────────────────────────────────────
  try { await requireAdmin(req); } catch (err) {
    return errorResponse(String(err), 403);
  }

  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  // ── Parse body ────────────────────────────────────────────
  let body: Record<string, unknown>;
  try { body = await req.json(); } catch {
    return errorResponse('Invalid JSON body', 400);
  }

  const candidateKey = typeof body.api_key === 'string' ? body.api_key.trim() : '';
  if (!candidateKey || candidateKey.length < 10) {
    return errorResponse('api_key is required and must be at least 10 characters.', 400);
  }

  // ── Step 1: Test candidate key — GET /balance ─────────────
  const authResult = await providerCallWithKey('balance', 'GET', candidateKey);
  const authPass = authResult.ok;

  await supabase.from('provider_logs').insert({
    operation: 'update_key_test_auth',
    success: authPass,
    http_status: authResult.httpStatus,
    provider_request_id: authResult.requestId ?? null,
    response_time_ms: authResult.responseTimeMs,
    error_code: authResult.errorCode ?? null,
    error_message: authResult.errorMessage ?? null,
  });

  if (!authPass) {
    return jsonResponse({
      success: false,
      stage: 'authentication',
      error: authResult.errorCode === 'AUTH_INVALID'
        ? 'المصادقة فشلت — مفتاح API غير صحيح.'
        : (authResult.errorMessage ?? 'فشل التحقق من المفتاح الجديد.'),
      key_updated: false,
    });
  }

  // ── Step 2: Test candidate key — GET /services ───────────
  const servicesResult = await providerCallWithKey('services', 'GET', candidateKey);
  const servicesPass = servicesResult.ok;

  await supabase.from('provider_logs').insert({
    operation: 'update_key_test_services',
    success: servicesPass,
    http_status: servicesResult.httpStatus,
    provider_request_id: servicesResult.requestId ?? null,
    response_time_ms: servicesResult.responseTimeMs,
    error_code: servicesResult.errorCode ?? null,
    error_message: servicesResult.errorMessage ?? null,
  });

  if (!servicesPass) {
    return jsonResponse({
      success: false,
      stage: 'get_services',
      error: servicesResult.errorMessage ?? 'لم تتمكن من جلب الخدمات بالمفتاح الجديد.',
      key_updated: false,
    });
  }

  // ── Step 3: Both passed — save key via Management API ────
  const projectRef = Deno.env.get('SUPABASE_URL')!
    .replace('https://', '').split('.')[0];
  const sbAdminKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;

  try {
    const secretsRes = await fetch(
      `https://api.supabase.com/v1/projects/${projectRef}/secrets`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${sbAdminKey}`,
        },
        body: JSON.stringify([{ name: 'PROVIDER_API_KEY', value: candidateKey }]),
      }
    );

    if (!secretsRes.ok) {
      await supabase.from('provider_logs').insert({
        operation: 'update_key_save',
        success: false,
        http_status: secretsRes.status,
        error_code: 'SECRETS_UPDATE_FAILED',
        error_message: 'Supabase Secrets API returned non-OK status.',
      });
      return jsonResponse({
        success: false,
        stage: 'save',
        error: 'فشل حفظ المفتاح على الخادم. المفتاح القديم لا يزال نشطًا.',
        key_updated: false,
      });
    }
  } catch {
    return jsonResponse({
      success: false,
      stage: 'save',
      error: 'خطأ شبكي أثناء حفظ المفتاح. المفتاح القديم لا يزال نشطًا.',
      key_updated: false,
    });
  }

  // ── Update provider_config ────────────────────────────────
  await supabase.from('provider_config').update({
    key_status: 'valid',
    environment: Deno.env.get('PROVIDER_ENVIRONMENT') ?? 'live',
    last_health_check_at: new Date().toISOString(),
    last_health_check_success: true,
  }).neq('id', '00000000-0000-0000-0000-000000000000');

  await supabase.from('provider_logs').insert({
    operation: 'update_key_save',
    success: true,
    http_status: 200,
  });

  // SECURITY: return masked prefix only — never the actual key
  return jsonResponse({
    success: true,
    key_updated: true,
    masked_key: getMaskedKeyPrefix(),
    message: 'تم تحديث مفتاح API بنجاح والاتصال مؤكد.',
    tests: {
      authentication: { result: 'PASS', response_time_ms: authResult.responseTimeMs },
      get_services: { result: 'PASS', response_time_ms: servicesResult.responseTimeMs },
    },
  });
});
