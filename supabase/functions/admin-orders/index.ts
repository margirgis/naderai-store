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

  // Fetch all orders for the admin app
  const { data: allOrdersData } = await db.rpc('get_all_orders_for_admin');
  const allOrders = (allOrdersData as any)?.orders ?? [];

  // Also fetch pending tasks for this device so SMS scanning can still work
  const { data: pendingData } = await db.rpc('get_device_pending_tasks', { p_device_id: deviceId });
  const pendingTasks = Array.isArray(pendingData) ? pendingData : ((pendingData as any)?.tasks ?? []);
  const commands = Array.isArray(pendingData) ? [] : ((pendingData as any)?.commands ?? []);

  return jsonResponse({
    ok: true,
    action: 'admin_orders',
    all_orders: allOrders,
    pending_tasks: pendingTasks,
    commands,
    tokens,
  });
});
