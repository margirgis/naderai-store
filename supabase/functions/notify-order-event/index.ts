/**
 * notify-order-event — Phase-3
 * POST { event_type, order_id, ... }
 *
 * الأحداث المدعومة (20):
 *   ORDER_CREATED / ORDER_QUEUED / ORDER_ELIGIBLE / DISPATCH_ATTEMPT
 *   DISPATCH_SUCCESS / DISPATCH_FAILED / ANDROID_SYNC / ORDER_RECEIVED
 *   SCAN_STARTED / SMS_SEARCH_STARTED / SMS_MATCH_FOUND / REVIEW_STARTED
 *   DUPLICATE_CHECK / AMOUNT_CHECK / SENDER_CHECK / SERVER_VERIFY
 *   CONFIRMATION / CREDIT_APPLIED / ORDER_COMPLETED / ERROR
 *
 * كل حدث يُسجَّل في order_events مع trace_id + duration_ms + retry_count
 * والأحداث المالية تُسجَّل في financial_audit_log أيضاً.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

type OrderEvent =
  | 'ORDER_CREATED' | 'ORDER_QUEUED' | 'ORDER_ELIGIBLE'
  | 'DISPATCH_ATTEMPT' | 'DISPATCH_SUCCESS' | 'DISPATCH_FAILED'
  | 'ANDROID_SYNC' | 'ORDER_RECEIVED'
  | 'SCAN_STARTED' | 'SMS_SEARCH_STARTED' | 'SMS_MATCH_FOUND'
  | 'REVIEW_STARTED' | 'DUPLICATE_CHECK' | 'AMOUNT_CHECK' | 'SENDER_CHECK'
  | 'SERVER_VERIFY' | 'CONFIRMATION' | 'CREDIT_APPLIED' | 'ORDER_COMPLETED'
  | 'ERROR'
  // Legacy (backward compat)
  | 'order_created' | 'order_processing' | 'payment_confirmed'
  | 'payment_failed' | 'order_expired' | 'credit_added';

interface EventPayload {
  event_type: OrderEvent;
  order_id: string;
  order_number?: number;
  trace_id?: string;
  user_id?: string;
  device_id?: string;
  amount?: number;
  credits?: number;
  reason?: string;
  error_code?: string;
  transaction_id?: string;
  sender_phone?: string;
  duration_ms?: number;
  retry_count?: number;
  actor?: string;
  status?: string;
  result?: string;
  metadata?: Record<string, unknown>;
}

// human-readable titles للـ notification bell
const TITLES: Record<string, string> = {
  ORDER_CREATED:     '📥 طلب شحن جديد',
  ORDER_QUEUED:      '⏳ طلب في الانتظار',
  ORDER_ELIGIBLE:    '✅ طلب جاهز للفحص',
  DISPATCH_ATTEMPT:  '📤 إرسال الطلب للجهاز',
  DISPATCH_SUCCESS:  '✅ وصل الطلب للجهاز',
  DISPATCH_FAILED:   '❌ فشل إرسال الطلب',
  ANDROID_SYNC:      '🔄 مزامنة الجهاز',
  ORDER_RECEIVED:    '📱 الجهاز استلم الطلب',
  SCAN_STARTED:      '🔍 بدأ فحص الرسائل',
  SMS_SEARCH_STARTED:'🔎 جاري البحث في SMS',
  SMS_MATCH_FOUND:   '📩 وُجدت رسالة مطابقة',
  REVIEW_STARTED:    '🧐 جاري المراجعة',
  DUPLICATE_CHECK:   '🔁 فحص التكرار',
  AMOUNT_CHECK:      '💰 فحص المبلغ',
  SENDER_CHECK:      '📱 فحص المرسل',
  SERVER_VERIFY:     '📤 إرسال للتحقق',
  CONFIRMATION:      '✅ تم تأكيد الدفع',
  CREDIT_APPLIED:    '💰 تم إضافة كريدت',
  ORDER_COMPLETED:   '🟢 اكتمل الطلب',
  ERROR:             '🚨 خطأ في الطلب',
  // Legacy
  order_created:     '📥 طلب شحن جديد',
  order_processing:  '🔍 بدأ فحص الطلب',
  payment_confirmed: '✅ تم تأكيد الدفع',
  payment_failed:    '❌ فشل الدفع',
  order_expired:     '⏰ انتهت صلاحية الطلب',
  credit_added:      '💰 تم إضافة كريدت',
};

function buildMessage(p: EventPayload): string {
  const num = p.order_number ? `#${p.order_number}` : p.order_id.slice(0, 8);
  const trace = p.trace_id ? ` [${p.trace_id.slice(0, 12)}]` : '';
  switch (p.event_type) {
    case 'ORDER_CREATED':
    case 'order_created':
      return `طلب ${num}${trace} — ${p.amount?.toFixed(2) ?? '?'} جنيه — ${p.credits ?? '?'} Credit`;
    case 'DISPATCH_ATTEMPT':
      return `طلب ${num}${trace} — الجهاز ${p.device_id?.slice(0, 12) ?? 'unknown'}`;
    case 'DISPATCH_SUCCESS':
      return `طلب ${num}${trace} — وصل للجهاز ${p.device_id?.slice(0, 12) ?? 'unknown'}`;
    case 'DISPATCH_FAILED':
      return `طلب ${num}${trace} — فشل الإرسال: ${p.reason ?? '?'}`;
    case 'SMS_MATCH_FOUND':
      return `طلب ${num}${trace} — عملية ${p.transaction_id?.slice(0, 12) ?? '?'}`;
    case 'DUPLICATE_CHECK':
      return `طلب ${num}${trace} — ${p.result === 'DUPLICATE' ? '⚠️ مكرر' : 'فريد ✓'}`;
    case 'AMOUNT_CHECK':
      return `طلب ${num}${trace} — ${p.status === 'ok' ? 'مبلغ مطابق ✓' : `مبلغ غير مطابق ⚠️ ${p.reason ?? ''}`}`;
    case 'CONFIRMATION':
    case 'payment_confirmed':
      return `طلب ${num}${trace} — ${p.amount?.toFixed(2) ?? '?'} جنيه مؤكد — ${p.credits ?? '?'} Credit`;
    case 'CREDIT_APPLIED':
    case 'credit_added':
      return `طلب ${num}${trace} — ${p.credits ?? '?'} Credit أُضيفت`;
    case 'ORDER_COMPLETED':
      return `طلب ${num}${trace} — اكتمل بنجاح`;
    case 'ERROR':
      return `طلب ${num}${trace} — ${p.error_code ?? 'ERROR'}: ${p.reason ?? '?'}`;
    case 'payment_failed':
      return `طلب ${num}${trace} — ${p.reason ?? 'سبب غير معروف'}`;
    case 'order_expired':
      return `طلب ${num}${trace} — انتهت الصلاحية`;
    default:
      return `طلب ${num}${trace} — ${p.event_type}`;
  }
}

// الأحداث التي تظهر في notification bell الأدمن
const NOTIFY_ADMIN = new Set([
  'ORDER_CREATED', 'order_created',
  'DISPATCH_SUCCESS', 'DISPATCH_FAILED',
  'SMS_MATCH_FOUND',
  'CONFIRMATION', 'payment_confirmed',
  'CREDIT_APPLIED', 'credit_added',
  'ORDER_COMPLETED',
  'ERROR', 'payment_failed', 'order_expired',
]);

// الأحداث المالية التي تُسجَّل في financial_audit_log
const FINANCIAL_EVENTS = new Set([
  'CONFIRMATION', 'CREDIT_APPLIED', 'ORDER_COMPLETED',
  'payment_confirmed', 'payment_failed', 'credit_added',
]);

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // ── Auth: service-role | webhook secret | admin JWT ──────────────────────
  const authHeader = req.headers.get('Authorization') ?? '';
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';
  const providedKey = authHeader.replace(/^Bearer\s+/i, '');
  const isInternal = providedKey === serviceKey;

  const webhookSecret = req.headers.get('X-SMS-Webhook-Secret') ?? '';
  const expectedWebhook = Deno.env.get('SMS_WEBHOOK_SECRET') ?? Deno.env.get('WEBHOOK_SECRET') ?? '';
  const isDevice = webhookSecret === expectedWebhook && expectedWebhook !== '';

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  if (!isInternal && !isDevice) {
    const db2 = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } },
    );
    const { data: { user } } = await db2.auth.getUser();
    if (!user) return errorResponse('Unauthorized', 401);
    const { data: prof } = await db.from('profiles').select('role').eq('id', user.id).single();
    if (prof?.role !== 'admin') return errorResponse('Forbidden', 403);
  }

  let payload: EventPayload;
  try { payload = await req.json(); } catch { return errorResponse('JSON غير صحيح', 400); }

  const { event_type, order_id } = payload;
  if (!event_type || !order_id) return errorResponse('event_type و order_id مطلوبان', 400);

  // ── 1. تسجيل في order_events دائماً ─────────────────────────────────────
  const { error: evtErr } = await db.rpc('log_order_event', {
    p_order_id:    order_id,
    p_order_number: payload.order_number ?? null,
    p_trace_id:    payload.trace_id ?? null,
    p_user_id:     payload.user_id ?? null,
    p_device_id:   payload.device_id ?? null,
    p_event_type:  event_type,
    p_status:      payload.status ?? null,
    p_result:      payload.result ?? null,
    p_reason:      payload.reason ?? null,
    p_error_code:  payload.error_code ?? null,
    p_duration_ms: payload.duration_ms ?? null,
    p_retry_count: (payload.retry_count ?? 0) as number,
    p_actor:       payload.actor ?? (payload.device_id ? `device:${payload.device_id}` : 'system'),
    p_metadata:    payload.metadata ?? {},
  });
  if (evtErr) console.error('[notify-order-event] order_events error:', evtErr.message);

  // ── 2. Admin bell notification للأحداث المرئية ───────────────────────────
  let notifOk = false;
  if (NOTIFY_ADMIN.has(event_type)) {
    const title   = TITLES[event_type] ?? `حدث: ${event_type}`;
    const message = buildMessage(payload);
    const { error: notifError } = await db.rpc('create_admin_notification', {
      p_title:        title,
      p_message:      message,
      p_event_type:   event_type,
      p_reference_id: order_id,
      p_device_id:    payload.device_id ?? null,
    });
    if (notifError) console.error('[notify-order-event] notification error:', notifError.message);
    else notifOk = true;
  }

  // ── 3. Financial audit log للأحداث المالية ───────────────────────────────
  let auditOk = false;
  if (FINANCIAL_EVENTS.has(event_type)) {
    const { error: auditErr } = await db.from('financial_audit_log').insert({
      event_type,
      order_id,
      transaction_id: payload.transaction_id ?? null,
      actor:  payload.actor ?? (payload.device_id ? `device:${payload.device_id}` : 'system'),
      amount: payload.amount ?? null,
      decision: payload.result ?? (event_type === 'CONFIRMATION' || event_type === 'payment_confirmed' ? 'confirmed' : null),
      reason: payload.reason ?? null,
      trace_id: payload.trace_id ?? null,
      metadata: {
        order_number:   payload.order_number,
        credits:        payload.credits,
        sender_phone:   payload.sender_phone,
        error_code:     payload.error_code,
      },
    });
    if (auditErr) console.error('[notify-order-event] audit_log error:', auditErr.message);
    else auditOk = true;
  }

  return jsonResponse({
    ok: true,
    event_type,
    order_id,
    trace_id: payload.trace_id ?? null,
    event_logged: !evtErr,
    notif_sent: notifOk,
    audit_logged: auditOk,
  });
});
