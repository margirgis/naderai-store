/**
 * cancel-payment-order — يلغي طلب دفع مفتوح
 * POST { order_id: string }
 * يتطلب JWT مستخدم مسجّل — يلغي فقط الطلبات التي هي في حالة awaiting_payment.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // ── Auth ──────────────────────────────────────────────────
  const authHeader = req.headers.get('Authorization') ?? '';
  const jwt = authHeader.replace(/^Bearer\s+/i, '');
  if (!jwt) return errorResponse('Unauthorized', 401);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data: { user }, error: authErr } = await db.auth.getUser(jwt);
  if (authErr || !user) return errorResponse('Unauthorized', 401);

  // ── Parse body ────────────────────────────────────────────
  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const order_id = typeof body.order_id === 'string' ? body.order_id : null;
  if (!order_id) return errorResponse('order_id مطلوب', 400);

  // ── تحقق من ملكية الطلب ──────────────────────────────────
  const { data: order, error: fetchErr } = await db
    .from('payment_orders')
    .select('id, status, user_id')
    .eq('id', order_id)
    .eq('user_id', user.id)
    .maybeSingle();

  if (fetchErr || !order) return errorResponse('الطلب غير موجود أو غير مصرح', 404);

  // ── لا يمكن إلغاء طلبات منتهية أو مؤكدة ─────────────────
  const nonCancellable = ['confirmed', 'cancelled', 'expired', 'failed', 'duplicate'];
  if (nonCancellable.includes(order.status)) {
    return errorResponse(`لا يمكن إلغاء طلب في حالة: ${order.status}`, 409);
  }

  // ── استدعاء RPC للإلغاء ───────────────────────────────────
  const { data, error: rpcErr } = await db.rpc('cancel_payment_order', {
    p_order_id: order_id,
    p_user_id: user.id,
  });

  if (rpcErr) {
    console.error('[cancel-payment-order] RPC error:', rpcErr.message);
    return errorResponse('تعذر إلغاء الطلب. حاول مرة أخرى.', 500);
  }

  if (!data?.ok) {
    return errorResponse(data?.reason ?? 'فشل إلغاء الطلب', 422);
  }

  return jsonResponse({ ok: true, order_id, status: 'cancelled' });
});
