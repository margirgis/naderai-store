import { createClient, type SupabaseClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

interface OrdersPayload {
  device_id?: string;
  access_token?: string;
  refresh_token?: string;
}

async function getValidUser(db: SupabaseClient, accessToken: string, refreshToken?: string) {
  const { data: userData, error: userError } = await db.auth.getUser(accessToken);
  if (userError || !userData.user) {
    if (!refreshToken) return { user: null, tokens: null };
    const { data: refreshData, error: refreshError } = await db.auth.refreshSession(refreshToken);
    if (refreshError || !refreshData.session) return { user: null, tokens: null };
    return {
      user: refreshData.user,
      tokens: {
        access_token: refreshData.session.access_token,
        refresh_token: refreshData.session.refresh_token,
        expires_at: refreshData.session.expires_at,
      },
    };
  }
  return { user: userData.user, tokens: null };
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  let payload: OrdersPayload;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  const deviceId = payload.device_id;
  const accessToken = payload.access_token;
  const refreshToken = payload.refresh_token;

  if (!deviceId) return errorResponse('device_id is required', 400);
  if (!accessToken) return errorResponse('access_token is required', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { user, tokens } = await getValidUser(db, accessToken, refreshToken);
  if (!user) return errorResponse('Invalid or expired session', 401);

  // Verify admin role
  const { data: profile, error: profileError } = await db
    .from('profiles')
    .select('role')
    .eq('id', user.id)
    .single();

  if (profileError || profile?.role !== 'admin') {
    return errorResponse('Unauthorized: admin role required', 403);
  }

  // Ensure device is registered/online so the admin app can still receive tasks
  await db.from('sms_device_status').upsert({
    device_id: deviceId,
    last_heartbeat_at: new Date().toISOString(),
    status: 'online',
    is_active: true,
  }, { onConflict: 'device_id' });

  // Assign pending requests to this device when no other device is handling them
  const { data: retryData } = await db.rpc('retry_pending_topup_requests', { p_device_id: deviceId });
  const retryResult = retryData as any ?? {};

  // Fetch all orders for the admin app
  const { data: allOrdersData } = await db.rpc('get_all_orders_for_admin');
  const allOrders = (allOrdersData as any)?.orders ?? [];

  // Also fetch pending tasks for this device so SMS scanning can still work.
  // get_device_pending_tasks returns RETURNS TABLE (rows array), NOT a JSON object.
  // BUG-FIX: Previous code did Array.isArray(pendingData) ? {} : pendingData which turned
  // the rows array into {} making pendingTasks always []. Now we handle both shapes:
  //   - RETURNS TABLE → Supabase client returns an array directly
  //   - Legacy JSON object shape → {pending_tasks: [], tasks: [], commands: []}
  const { data: pendingData, error: pendingError } = await db.rpc('get_device_pending_tasks', { p_device_id: deviceId });
  if (pendingError) {
    console.error(`[admin-orders] get_device_pending_tasks error: ${pendingError.message}`);
  }
  let pendingTasks: any[] = [];
  let commands: any[] = [];
  if (Array.isArray(pendingData)) {
    // RETURNS TABLE shape — rows come back as a plain array
    pendingTasks = pendingData;
  } else if (pendingData && typeof pendingData === 'object') {
    // Legacy JSON object shape
    const obj = pendingData as any;
    pendingTasks = obj.pending_tasks ?? obj.tasks ?? [];
    commands = obj.commands ?? [];
  }
  console.log(`[admin-orders] device_id=${deviceId} pending_tasks returned=${pendingTasks.length} commands=${commands.length}`);

  return jsonResponse({
    ok: true,
    action: 'admin_orders',
    all_orders: allOrders,
    pending_tasks: pendingTasks,
    commands,
    tokens,
    newly_dispatched: retryResult?.dispatched ?? 0,
    reassigned_from_offline: retryResult?.reassigned_from_offline ?? 0,
  });
});
