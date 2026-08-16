/**
 * create-payment-order — ينشئ طلب دفع آمن Server-Side
 * POST { credits_qty, offer_id?, idempotency_key }
 *
 * ما لا يُقبل من العميل:
 *   - fingerprint / auto_identifier
 *   - expected_amount
 *   - base_amount
 *   - status
 *   - order_id
 *
 * Server يولد fingerprint عشوائياً غير مكرر (حلقة 99 قيمة)
 * وإذا كان للمستخدم طلب مفتوح يُعاد نفس الطلب.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // ── Auth: مستخدم مسجّل فقط ──────────────────────────────
  const authHeader = req.headers.get('Authorization') ?? '';
  const jwt = authHeader.replace(/^Bearer\s+/i, '');
  if (!jwt) return errorResponse('Unauthorized', 401);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  // تحقق من JWT وجلب user_id
  const { data: { user }, error: authErr } = await db.auth.getUser(jwt);
  if (authErr || !user) return errorResponse('Unauthorized', 401);

  // ── Parse body ───────────────────────────────────────────
  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  // ── Validate: لا نقبل fingerprint أو expected_amount من العميل
  const forbidden = ['fingerprint', 'auto_identifier', 'expected_amount', 'base_amount', 'status', 'order_id'];
  for (const f of forbidden) {
    if (body[f] !== undefined) {
      // سجّل محاولة التلاعب
      await db.from('security_audit_log').insert({
        event_type: `tamper_field_${f}`,
        user_id: user.id,
        details: { attempted_field: f, value: body[f] },
      });
      return errorResponse(`الحقل ${f} غير مسموح بإرساله من العميل`, 400);
    }
  }

  const credits_qty = Number(body.credits_qty);
  if (!Number.isInteger(credits_qty) || credits_qty < 1) {
    return errorResponse('عدد الكريدات يجب أن يكون رقماً صحيحاً أكبر من صفر', 400);
  }

  const offer_id      = typeof body.offer_id === 'string'        ? body.offer_id        : null;
  const idempotency_key = typeof body.idempotency_key === 'string' ? body.idempotency_key : null;

  // ── Call RPC: ينشئ الطلب أو يُعيد الطلب المفتوح ─────────
  const { data, error } = await db.rpc('create_payment_order', {
    p_user_id:         user.id,
    p_credits_qty:     credits_qty,
    p_offer_id:        offer_id,
    p_idempotency_key: idempotency_key,
  });

  if (error) {
    console.error('[create-payment-order] RPC error:', error.message);
    return errorResponse('تعذر إنشاء الطلب. حاول مرة أخرى.', 500);
  }

  if (!data?.ok) {
    return errorResponse(data?.reason ?? 'فشل إنشاء الطلب', 422);
  }

  return jsonResponse(data);
});
