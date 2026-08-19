/**
 * notify-order-event
 * POST { event_type, order_id, user_id?, amount?, credits?, reason?, device_id? }
 *
 * Handles 6 event types:
 *   order_created        → يعلم الأدمن بطلب جديد
 *   order_processing     → بدأ الفحص التلقائي
 *   payment_confirmed    → تم تأكيد الدفع وإضافة الكريدت
 *   payment_failed       → فشل الدفع (مع السبب)
 *   order_expired        → انتهت صلاحية الطلب
 *   credit_added         → تم إضافة كريدت (يدوي أو تلقائي)
 *
 * Called from DB triggers OR Edge Functions after each state transition.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

type OrderEvent =
  | 'order_created'
  | 'order_processing'
  | 'payment_confirmed'
  | 'payment_failed'
  | 'order_expired'
  | 'credit_added';

interface EventPayload {
  event_type: OrderEvent;
  order_id: string;
  order_number?: number;
  user_id?: string;
  amount?: number;
  credits?: number;
  reason?: string;
  device_id?: string;
  transaction_id?: string;
  sender_phone?: string;
}

const TITLES: Record<OrderEvent, string> = {
  order_created:     '📥 طلب شحن جديد',
  order_processing:  '🔍 بدأ فحص الطلب',
  payment_confirmed: '✅ تم تأكيد الدفع',
  payment_failed:    '❌ فشل الدفع',
  order_expired:     '⏰ انتهت صلاحية الطلب',
  credit_added:      '💰 تم إضافة كريدت',
};

function buildMessage(p: EventPayload): string {
  const num = p.order_number ? `#${p.order_number}` : p.order_id.slice(0, 8);
  switch (p.event_type) {
    case 'order_created':
      return `طلب ${num} — ${p.amount?.toFixed(2) ?? '?'} جنيه — ${p.credits ?? '?'} Credit`;
    case 'order_processing':
      return `طلب ${num} — الجهاز ${p.device_id?.slice(0, 12) ?? 'unknown'} يفحص الآن`;
    case 'payment_confirmed':
      return `طلب ${num} — ${p.amount?.toFixed(2) ?? '?'} جنيه مؤكد — ${p.credits ?? '?'} Credit أُضيفت${p.sender_phone ? ' من ' + p.sender_phone : ''}`;
    case 'payment_failed':
      return `طلب ${num} — ${p.reason ?? 'سبب غير معروف'}`;
    case 'order_expired':
      return `طلب ${num} — انتهت الصلاحية دون تأكيد`;
    case 'credit_added':
      return `طلب ${num} — ${p.credits ?? '?'} Credit أُضيفت للعميل`;
    default:
      return `طلب ${num}`;
  }
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // Auth: service-role secret (called internally) or admin JWT
  const authHeader = req.headers.get('Authorization') ?? '';
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';
  const providedKey = authHeader.replace(/^Bearer\s+/i, '');
  const isInternal = providedKey === serviceKey;

  // Also accept webhook secret for device-originated events
  const webhookSecret = req.headers.get('X-SMS-Webhook-Secret') ?? '';
  const expectedWebhook = Deno.env.get('SMS_WEBHOOK_SECRET') ?? Deno.env.get('WEBHOOK_SECRET') ?? '';
  const isDevice = webhookSecret === expectedWebhook && expectedWebhook !== '';

  if (!isInternal && !isDevice) {
    // Try admin JWT fallback
    const db2 = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } },
    );
    const { data: { user } } = await db2.auth.getUser();
    if (!user) return errorResponse('Unauthorized', 401);
    // Check admin role
    const dbCheck = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
      { auth: { persistSession: false } },
    );
    const { data: prof } = await dbCheck.from('profiles').select('role').eq('id', user.id).single();
    if (prof?.role !== 'admin') return errorResponse('Forbidden', 403);
  }

  let payload: EventPayload;
  try { payload = await req.json(); } catch { return errorResponse('JSON غير صحيح', 400); }

  const { event_type, order_id } = payload;
  if (!event_type || !order_id) return errorResponse('event_type و order_id مطلوبان', 400);

  const title = TITLES[event_type] ?? `حدث: ${event_type}`;
  const message = buildMessage(payload);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  // 1. Admin notification (dashboard bell)
  const { error: notifError } = await db.rpc('create_admin_notification', {
    p_title:        title,
    p_message:      message,
    p_event_type:   event_type,
    p_reference_id: order_id,
    p_device_id:    payload.device_id ?? null,
  });

  if (notifError) {
    console.error('[notify-order-event] notification error:', notifError.message);
  }

  // 2. Financial audit log for financial events
  if (['payment_confirmed', 'payment_failed', 'credit_added'].includes(event_type)) {
    await db.from('financial_audit_log').insert({
      event_type,
      order_id,
      transaction_id: payload.transaction_id ?? null,
      actor: payload.device_id ? `device:${payload.device_id}` : 'system',
      amount: payload.amount ?? null,
      metadata: {
        order_number: payload.order_number,
        credits: payload.credits,
        reason: payload.reason,
        sender_phone: payload.sender_phone,
      },
    });
  }

  return jsonResponse({
    ok: true,
    event_type,
    order_id,
    title,
    message,
  });
});
