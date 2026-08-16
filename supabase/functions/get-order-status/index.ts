/**
 * get-order-status — Server-side polling fallback
 * POST { order_id }
 * Respects rate limit, retry limit, terminal status check.
 * Browser NEVER calls provider directly.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAuth } from '../_shared/auth-user.ts';
import { providerCall } from '../_shared/provider-client.ts';

const TERMINAL = new Set(['success', 'partial', 'failed', 'cancelled', 'rejected']);
const MAX_POLL_COUNT = 20; // stop polling after 20 attempts per order
const POLL_INTERVAL_MIN_MS = 10_000; // minimum 10s between polls

async function pushNotification(db: ReturnType<typeof createClient>, params: {
  userId: string; type: string; title: string; body: string; orderId?: string;
}) {
  try {
    // Deduplicate: don't send same event twice for same order
    const { count } = await db.from('notifications')
      .select('id', { count: 'exact', head: true })
      .eq('user_id', params.userId)
      .eq('order_id', params.orderId ?? '')
      .eq('type', params.type);
    if ((count ?? 0) === 0) {
      await db.from('notifications').insert({
        user_id: params.userId, type: params.type,
        title: params.title, body: params.body,
        order_id: params.orderId ?? null,
      });
    }
  } catch { /* silent */ }
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  let userId: string, role: string;
  try { const a = await requireAuth(req); userId = a.userId; role = a.role; }
  catch (e) { return errorResponse(String(e), 401); }

  let body: { order_id?: string };
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }
  if (!body.order_id) return errorResponse('order_id مطلوب', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  const { data: order } = await db
    .from('orders')
    .select('id, customer_id, provider_task_id, status, result_data, offer_link, poll_count, last_polled_at, updated_at')
    .eq('id', body.order_id)
    .maybeSingle();

  if (!order) return errorResponse('الطلب غير موجود', 404);
  if (role !== 'admin' && order.customer_id !== userId) return errorResponse('غير مصرح', 403);

  // Already terminal
  if (TERMINAL.has(order.status)) {
    return jsonResponse({ status: order.status, offer_link: order.offer_link ?? null, polled: false });
  }

  // No task_id yet
  if (!order.provider_task_id) {
    return jsonResponse({ status: order.status, offer_link: null, polled: false });
  }

  // Rate limit: honour minimum poll interval
  const pollCount = order.poll_count ?? 0;
  const lastPolled = order.last_polled_at ? new Date(order.last_polled_at).getTime() : 0;
  const now = Date.now();
  if (now - lastPolled < POLL_INTERVAL_MIN_MS) {
    return jsonResponse({ status: order.status, offer_link: order.offer_link ?? null, polled: false, rate_limited: true });
  }
  if (pollCount >= MAX_POLL_COUNT) {
    return jsonResponse({ status: order.status, offer_link: order.offer_link ?? null, polled: false, max_polls_reached: true });
  }

  // Update poll metadata first
  await db.from('orders').update({
    poll_count: pollCount + 1,
    last_polled_at: new Date().toISOString(),
  }).eq('id', order.id);

  // Poll provider
  const pollResult = await providerCall<{
    status?: string;
    offer_link?: string;
    two_fa_link?: string;
    message?: string;
  }>(`orders/${order.provider_task_id}`, 'GET');

  if (!pollResult.ok) {
    return jsonResponse({ status: order.status, offer_link: order.offer_link ?? null, polled: true, provider_reachable: false });
  }

  const pd = pollResult.data ?? {};
  const pStatus = (pd.status ?? '').toLowerCase();

  let newStatus = order.status;
  if (pStatus === 'success' || pStatus === 'completed') newStatus = 'success';
  else if (pStatus === 'partial') newStatus = 'partial';
  else if (pStatus === 'failed' || pStatus === 'error') newStatus = 'failed';
  else if (pStatus === 'rejected') newStatus = 'rejected';
  else if (pStatus === 'processing' || pStatus === 'in_progress') newStatus = 'processing';
  else if (pStatus === 'queued' || pStatus === 'pending') newStatus = 'queued';

  const newOfferLink = pd.offer_link ?? pd.two_fa_link ?? order.offer_link ?? null;
  const isComplete = TERMINAL.has(newStatus);
  const statusChanged = newStatus !== order.status;

  if (statusChanged || newOfferLink !== order.offer_link) {
    const safeResult = { ...(order.result_data ?? {}), message: pd.message };
    if (newOfferLink) safeResult.offer_link = newOfferLink;

    // offer_link_created_at: set server-side only on first assignment
    const offerLinkCreatedAt =
      newOfferLink && !order.offer_link ? new Date().toISOString() : undefined;

    const updateFields: Record<string, unknown> = {
      status: newStatus,
      offer_link: newOfferLink,
      result_data: safeResult,
      result_available: newStatus === 'success' || newStatus === 'partial',
      completed_at: isComplete ? new Date().toISOString() : null,
      updated_at: new Date().toISOString(),
    };
    if (offerLinkCreatedAt) updateFields.offer_link_created_at = offerLinkCreatedAt;

    await db.from('orders').update(updateFields).eq('id', order.id);

    // Notifications
    if (newStatus === 'success') {
      await pushNotification(db, {
        userId: order.customer_id, type: 'order_success', orderId: order.id,
        title: 'تم اكتمال طلبك 🎉',
        body: 'طلبك اكتمل بنجاح!',
      });
      if (newOfferLink) {
        await pushNotification(db, {
          userId: order.customer_id, type: 'offer_link_ready', orderId: order.id,
          title: 'رابط التفعيل جاهز 🎁',
          body: 'رابط تفعيل الاشتراك متاح الآن. افتح تفاصيل الطلب.',
        });
      }
    } else if (newStatus === 'failed' || newStatus === 'rejected') {
      await pushNotification(db, {
        userId: order.customer_id, type: 'order_failed', orderId: order.id,
        title: 'تعذر تنفيذ طلبك ⚠️',
        body: 'تعذر تنفيذ الطلب. تواصل مع الدعم إذا استمر الأمر.',
      });
    } else if (statusChanged) {
      await pushNotification(db, {
        userId: order.customer_id, type: 'order_updated', orderId: order.id,
        title: 'تحديث الطلب 🔄',
        body: `حالة طلبك تحدّثت إلى: ${newStatus}`,
      });
    }
  }

  return jsonResponse({
    status: newStatus,
    offer_link: newOfferLink,
    polled: true,
    status_changed: statusChanged,
  });
});
