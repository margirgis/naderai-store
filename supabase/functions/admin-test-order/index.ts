/**
 * admin-test-order — ينشئ طلب شحن حقيقي للاختبار من لوحة الأدمن
 *
 * الفرق عن create-payment-order:
 *  • المُستدعي هو الأدمن (وليس العميل صاحب الطلب)
 *  • السعر (expected_amount) يُحدَّد يدوياً بالكامل من الأدمن
 *  • fingerprint = 0.00 (لا يوجد قرش قائد — الأدمن يكتب المبلغ بالظبط)
 *  • يُضاف حقل is_test_order = true في metadata ليُميَّز في لوحة التحكم
 *
 * POST body:
 *  { customer_id, credits_qty, exact_amount, note? }
 *
 * Returns: { ok, order_id, order_number, expected_amount, credits_qty, expires_at }
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // ── تحقق أن المُستدعي أدمن ──────────────────────────────
  let adminId: string;
  try {
    adminId = await requireAdmin(req);
  } catch (e) {
    return errorResponse(String(e), 403);
  }

  // ── Parse body ───────────────────────────────────────────
  let body: {
    customer_id?: string;
    credits_qty?: number;
    exact_amount?: number;
    note?: string;
  };
  try {
    body = await req.json();
  } catch {
    return errorResponse('طلب غير صحيح', 400);
  }

  const { customer_id, credits_qty, exact_amount, note } = body;

  if (!customer_id || typeof customer_id !== 'string') {
    return errorResponse('customer_id مطلوب', 400);
  }
  const qty = Number(credits_qty);
  if (!Number.isInteger(qty) || qty < 1) {
    return errorResponse('credits_qty يجب أن يكون رقماً صحيحاً أكبر من صفر', 400);
  }
  const amount = Number(exact_amount);
  if (!isFinite(amount) || amount <= 0) {
    return errorResponse('exact_amount يجب أن يكون رقماً موجباً', 400);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  // ── التحقق أن العميل موجود وليس أدمن ────────────────────
  const { data: customerProfile, error: profileErr } = await db
    .from('profiles')
    .select('id, role, email, full_name')
    .eq('id', customer_id)
    .maybeSingle();

  if (profileErr || !customerProfile) {
    return errorResponse('العميل غير موجود', 404);
  }

  // ── إنشاء الطلب مباشرة بمبلغ يدوي (fingerprint = 0.00) ──
  const expiresAt = new Date(Date.now() + 30 * 60 * 1000).toISOString(); // 30 دقيقة
  const idempotencyKey = `admin-test-${adminId}-${Date.now()}`;

  const { data: order, error: insertErr } = await db
    .from('payment_orders')
    .insert({
      user_id: customer_id,
      credits_qty: qty,
      base_amount: amount,
      discount_amount: 0,
      fingerprint: 0.00,
      expected_amount: amount,
      status: 'awaiting_payment',
      idempotency_key: idempotencyKey,
      expires_at: expiresAt,
      // حقل metadata لتمييز طلبات الاختبار
      metadata: {
        is_test_order: true,
        created_by_admin: adminId,
        note: note ?? null,
      },
    })
    .select('id, order_number, expected_amount, credits_qty, expires_at, status')
    .single();

  if (insertErr || !order) {
    console.error('[admin-test-order] insert error:', insertErr?.message);
    return errorResponse('تعذر إنشاء الطلب: ' + (insertErr?.message ?? 'خطأ غير معروف'), 500);
  }

  // ── تسجيل في admin_audit_log ─────────────────────────────
  await db.from('admin_audit_log').insert({
    admin_id: adminId,
    action: 'create_test_order',
    target_type: 'payment_order',
    target_id: order.id,
    details: {
      customer_id,
      credits_qty: qty,
      exact_amount: amount,
      note: note ?? null,
    },
  }).then(() => {}); // non-blocking

  return jsonResponse({
    ok: true,
    order_id: order.id,
    order_number: order.order_number,
    expected_amount: order.expected_amount,
    credits_qty: order.credits_qty,
    expires_at: order.expires_at,
    status: order.status,
  });
});
