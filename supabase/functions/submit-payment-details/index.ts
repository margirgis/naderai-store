/**
 * submit-payment-details — المستخدم يُرسل رقم محفظته واسم صاحبها
 * POST { order_id, sender_phone, sender_name? }
 *
 * Server يُنشئ wallet_topup_request ويُرسل مهمة للـ Android
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

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

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const order_id     = typeof body.order_id     === 'string' ? body.order_id     : null;
  const sender_phone = typeof body.sender_phone === 'string' ? body.sender_phone.trim() : null;
  const sender_name  = typeof body.sender_name  === 'string' ? body.sender_name.trim()  : null;

  if (!order_id) return errorResponse('order_id مطلوب', 400);
  if (!sender_phone) return errorResponse('رقم المحفظة مطلوب', 400);

  // Reject any attempts to pass financial values
  const forbidden = ['fingerprint', 'auto_identifier', 'expected_amount',
                     'amount', 'credits_qty', 'base_amount', 'status'];
  for (const f of forbidden) {
    if (body[f] !== undefined) {
      await db.from('security_audit_log').insert({
        event_type: `tamper_submit_${f}`,
        user_id: user.id,
        order_id,
        details: { attempted_field: f, value: body[f] },
      });
      return errorResponse(`الحقل ${f} غير مسموح بإرساله`, 400);
    }
  }

  // ── RPC: يُحقق من الطلب ويُنشئ wallet_topup_request ─────
  const { data, error } = await db.rpc('submit_payment_details', {
    p_order_id:    order_id,
    p_user_id:     user.id,
    p_sender_phone: sender_phone,
    p_sender_name:  sender_name,
  });

  if (error) {
    console.error('[submit-payment-details] RPC error:', error.message);
    return errorResponse('تعذر إرسال بيانات الدفع.', 500);
  }

  if (!data?.ok) {
    const messages: Record<string, string> = {
      order_not_found:              'الطلب غير موجود',
      order_not_in_awaiting_payment: 'الطلب ليس في حالة انتظار الدفع',
      order_expired:                'انتهت صلاحية الطلب',
    };
    return errorResponse(messages[data?.reason] ?? data?.reason ?? 'فشل إرسال البيانات', 422);
  }

  return jsonResponse(data);
});
