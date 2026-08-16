import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';
import { providerCall } from '../_shared/provider-client.ts';

interface ProviderService {
  code: string;
  name: string;
  status: string;
  price?: {
    credit?: number | string;
    idr?: number | string;
    usd?: number | string;
  };
  discount_percent?: number | string;
  final_price?: number | string;
  input?: string;
  max_items_per_request?: number;
  returns?: boolean;
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

  const result = await providerCall<ProviderService[]>('services', 'GET');

  // Parse services FIRST, then log
  let services: ProviderService[] = [];
  if (Array.isArray(result.data)) {
    services = result.data;
  } else if (result.data && typeof result.data === 'object') {
    const d = result.data as Record<string, unknown>;
    const nested = d['services'] ?? d['data'] ?? d['items'] ?? d['result'];
    if (Array.isArray(nested)) services = nested as ProviderService[];
  }

  // Log with accurate parsed count
  await supabase.from('provider_logs').insert({
    operation: 'sync_services',
    success: result.ok,
    http_status: result.httpStatus,
    provider_request_id: result.requestId ?? null,
    response_time_ms: result.responseTimeMs,
    error_code: result.errorCode ?? null,
    error_message: result.errorMessage ?? null,
    extra_data: {
      raw_data_type: Array.isArray(result.data) ? 'array' : typeof result.data,
      raw_keys: result.data && typeof result.data === 'object' ? Object.keys(result.data as object) : [],
      parsed_services_count: services.length,
    },
  });

  if (!result.ok) {
    return jsonResponse({
      success: false,
      error: result.errorMessage,
      error_code: result.errorCode,
    }, 200);
  }

  const syncedAt = new Date().toISOString();
  let errors = 0;

  for (const svc of services) {
    if (!svc.code) continue;

    // Normalize status: API uses "available" → map to "active"
    const rawStatus = String(svc.status ?? 'active');
    const normalizedStatus = rawStatus === 'available' ? 'active' : rawStatus;

    // final_price can be object {credit,idr,usd} or scalar
    const finalPrice = svc.final_price;
    const finalCreditPrice = finalPrice != null
      ? (typeof finalPrice === 'object' && !Array.isArray(finalPrice)
          ? Number((finalPrice as Record<string, unknown>)['credit'] ?? 0)
          : Number(finalPrice))
      : null;

    // returns can be boolean or array of strings
    const returnsVal = Array.isArray(svc.returns)
      ? svc.returns.length > 0
      : Boolean(svc.returns ?? false);

    const row = {
      provider_code: String(svc.code),
      name: svc.name ?? svc.code,
      status: normalizedStatus,
      input_type: svc.input ?? null,
      provider_credit_price: svc.price?.credit != null ? Number(svc.price.credit) : null,
      provider_idr_price:    svc.price?.idr   != null ? Number(svc.price.idr)    : null,
      provider_usd_price:    svc.price?.usd   != null ? Number(svc.price.usd)    : null,
      discount_percent: svc.discount_percent != null ? Number(svc.discount_percent) : null,
      final_credit_price: finalCreditPrice,
      max_items_per_request: svc.max_items_per_request ?? null,
      returns: returnsVal,
      last_synced_at: syncedAt,
      is_enabled: rawStatus !== 'maintenance' && rawStatus !== 'inactive',
    };

    // Upsert: update existing, insert new
    const { error, data } = await supabase
      .from('provider_services')
      .upsert(row, { onConflict: 'provider_code', ignoreDuplicates: false })
      .select('id');

    if (error) {
      errors++;
      console.error(`Sync error for ${svc.code}:`, error.message);
    }
  }

  // Count totals
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

  // Persist stats to provider_config for live dashboard
  await supabase.from('provider_config').update({
    services_count: total ?? 0,
    services_available: available ?? 0,
    services_maintenance: maintenance ?? 0,
    stats_synced_at: syncedAt,
    environment: Deno.env.get('PROVIDER_ENVIRONMENT') ?? 'live',
  }).neq('id', '00000000-0000-0000-0000-000000000000');

  return jsonResponse({
    success: true,
    synced: services.length,
    errors,
    total_in_db: total ?? 0,
    available: available ?? 0,
    maintenance: maintenance ?? 0,
    synced_at: syncedAt,
    provider_request_id: result.requestId ?? null,
  });
});
