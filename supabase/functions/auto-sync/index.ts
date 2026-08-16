/**
 * auto-sync — Server-side scheduled sync: balance + services.
 * Called by Supabase pg_cron every 5 minutes (configured via DB migration).
 * SECURITY: Uses service_role — no user auth required (internal schedule only).
 * Never logs API keys. Never returns sensitive data.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { providerCall } from '../_shared/provider-client.ts';
import { handleCors, jsonResponse } from '../_shared/cors.ts';

const CRON_SECRET = Deno.env.get('CRON_SECRET') ?? '';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // Validate cron secret header to prevent unauthorized triggers
  const authHeader = req.headers.get('Authorization') ?? '';
  const cronSecret = req.headers.get('X-Cron-Secret') ?? '';
  const isServiceRole = authHeader.includes(Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '__none__');
  const isCron = CRON_SECRET && cronSecret === CRON_SECRET;

  if (!isServiceRole && !isCron && Deno.env.get('DENO_ENV') !== 'development') {
    return jsonResponse({ error: 'Unauthorized' }, 401);
  }

  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  const syncedAt = new Date().toISOString();
  const results: Record<string, unknown> = { synced_at: syncedAt };

  // ── 1. Sync Balance ──────────────────────────────────────
  try {
    const balResult = await providerCall<{
      credit?: number | string;
      currency?: string;
    }>('balance', 'GET');

    await supabase.from('provider_logs').insert({
      operation: 'auto_sync_balance',
      success: balResult.ok,
      http_status: balResult.httpStatus,
      response_time_ms: balResult.responseTimeMs,
      error_code: balResult.errorCode ?? null,
      error_message: balResult.errorMessage ?? null,
    });

    if (balResult.ok && balResult.data) {
      const bd = balResult.data;
      await supabase
        .from('provider_config')
        .update({
          balance_credit: bd.credit != null ? Number(bd.credit) : null,
          balance_currency: bd.currency ?? 'CREDIT',
          balance_synced_at: syncedAt,
          last_health_check_at: syncedAt,
          last_health_check_success: true,
          key_status: 'valid',
        })
        .neq('id', '00000000-0000-0000-0000-000000000000');
    }
    results.balance = { success: balResult.ok, response_ms: balResult.responseTimeMs };
  } catch (e) {
    results.balance = { success: false, error: 'exception' };
  }

  // ── 2. Sync Services ─────────────────────────────────────
  try {
    const svcResult = await providerCall<unknown>('services', 'GET');

    // Parse flexible response shape
    let services: Array<Record<string, unknown>> = [];
    if (Array.isArray(svcResult.data)) {
      services = svcResult.data as Array<Record<string, unknown>>;
    } else if (svcResult.data && typeof svcResult.data === 'object') {
      const d = svcResult.data as Record<string, unknown>;
      const nested = d['services'] ?? d['data'] ?? d['items'] ?? d['result'];
      if (Array.isArray(nested)) services = nested as Array<Record<string, unknown>>;
    }

    let upserted = 0;
    let errors = 0;
    for (const svc of services) {
      if (!svc['code']) continue;

      // Normalize status: API uses "available" → map to "active"
      const rawStatus = String(svc['status'] ?? 'active');
      const normalizedStatus = rawStatus === 'available' ? 'active' : rawStatus;

      // final_price can be object {credit,idr,usd} or scalar
      const finalPrice = svc['final_price'];
      const finalCreditPrice = finalPrice != null
        ? (typeof finalPrice === 'object' && !Array.isArray(finalPrice)
            ? Number((finalPrice as Record<string, unknown>)['credit'] ?? 0)
            : Number(finalPrice))
        : null;

      // returns can be boolean or array of strings
      const returnsRaw = svc['returns'];
      const returnsVal = Array.isArray(returnsRaw)
        ? returnsRaw.length > 0
        : Boolean(returnsRaw ?? false);

      const row = {
        provider_code: String(svc['code']),
        name: (svc['name'] as string) ?? String(svc['code']),
        status: normalizedStatus,
        input_type: (svc['input'] as string) ?? null,
        provider_credit_price: svc['price'] && typeof svc['price'] === 'object'
          ? Number((svc['price'] as Record<string, unknown>)['credit'] ?? 0) : null,
        provider_idr_price: svc['price'] && typeof svc['price'] === 'object'
          ? Number((svc['price'] as Record<string, unknown>)['idr'] ?? 0) : null,
        provider_usd_price: svc['price'] && typeof svc['price'] === 'object'
          ? Number((svc['price'] as Record<string, unknown>)['usd'] ?? 0) : null,
        discount_percent: svc['discount_percent'] != null ? Number(svc['discount_percent']) : null,
        final_credit_price: finalCreditPrice,
        max_items_per_request: svc['max_items_per_request'] != null
          ? Number(svc['max_items_per_request']) : null,
        returns: returnsVal,
        is_enabled: rawStatus !== 'maintenance' && rawStatus !== 'inactive',
        last_synced_at: syncedAt,
      };

      const { error } = await supabase
        .from('provider_services')
        .upsert(row, { onConflict: 'provider_code', ignoreDuplicates: false });
      if (error) errors++;
      else upserted++;
    }

    await supabase.from('provider_logs').insert({
      operation: 'auto_sync_services',
      success: svcResult.ok,
      http_status: svcResult.httpStatus,
      response_time_ms: svcResult.responseTimeMs,
      error_code: svcResult.errorCode ?? null,
      error_message: svcResult.errorMessage ?? null,
      extra_data: { parsed: services.length, upserted, errors },
    });

    // Persist stats counts to provider_config
    if (svcResult.ok) {
      const { count: total } = await supabase
        .from('provider_services').select('*', { count: 'exact', head: true });
      const { count: available } = await supabase
        .from('provider_services').select('*', { count: 'exact', head: true }).eq('status', 'active');
      const { count: maintenance } = await supabase
        .from('provider_services').select('*', { count: 'exact', head: true }).eq('status', 'maintenance');

      await supabase.from('provider_config').update({
        services_count: total ?? 0,
        services_available: available ?? 0,
        services_maintenance: maintenance ?? 0,
        stats_synced_at: syncedAt,
        environment: Deno.env.get('PROVIDER_ENVIRONMENT') ?? 'live',
      }).neq('id', '00000000-0000-0000-0000-000000000000');
    }

    results.services = { success: svcResult.ok, parsed: services.length, upserted, errors };
  } catch (e) {
    results.services = { success: false, error: 'exception' };
  }

  return jsonResponse({ ok: true, ...results });
});
