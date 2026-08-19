/**
 * admin-test-order — ينشئ طلب شحن حقيقي كامل من لوحة الأدمن
 *
 * يعمل بنفس آلية submit_payment_details:
 *  1. INSERT payment_orders بمبلغ يدوي (fingerprint = 0.00)
 *  2. UPDATE status → 'scanning'
 *  3. INSERT wallet_topup_requests (ليُرسَل للجهاز تلقائياً عبر trigger)
 *  4. الـ auto_dispatch_topup_request trigger يُرسل المهمة للجهاز فوراً
 *
 * POST body:
 *  { customer_id, credits_qty, exact_amount, sender_phone, sender_name?, note? }
 *
 * Returns:
 *  { ok, order_id, order_number, expected_amount, credits_qty,
 *    topup_request_id, expires_at, customer_name, customer_email, sender_phone }
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
    sender_phone?: string;
    sender_name?: string;
    note?: string;
  };
  try {
    body = await req.json();
  } catch {
    return errorResponse('طلب غير صحيح', 400);
  }

  const { customer_id, credits_qty, exact_amount, sender_phone, sender_name, note } = body;

  if (!customer_id || typeof customer_id !== 'string') {
    return errorResponse('customer_id مطلوب', 400);
  }
  const qty = Number(credits_qty);
  if (!Number.isInteger(qty) || qty < 1) {
    return errorResponse('عدد الكريدت يجب أن يكون رقماً صحيحاً أكبر من صفر', 400);
  }
  const amount = Number(exact_amount);
  if (!isFinite(amount) || amount <= 0) {
    return errorResponse('المبلغ يجب أن يكون رقماً موجباً', 400);
  }
  if (!sender_phone || typeof sender_phone !== 'string' || sender_phone.trim().length < 8) {
    return errorResponse('رقم المحوّل (sender_phone) مطلوب', 400);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  // ── التحقق أن العميل موجود ───────────────────────────────
  const { data: customerProfile, error: profileErr } = await db
    .from('profiles')
    .select('id, role, email, full_name, phone')
    .eq('id', customer_id)
    .maybeSingle();

  if (profileErr || !customerProfile) {
    return errorResponse('العميل غير موجود', 404);
  }

  // ── 1. إنشاء payment_order بمبلغ يدوي دقيق ──────────────
  const expiresAt = new Date(Date.now() + 30 * 60 * 1000).toISOString(); // 30 دقيقة
  const idempotencyKey = `admin-test-${adminId}-${Date.now()}`;
  const cleanPhone = sender_phone.trim();
  const cleanName  = (sender_name ?? '').trim() || null;

  const { data: order, error: insertErr } = await db
    .from('payment_orders')
    .insert({
      user_id:          customer_id,
      credits_qty:      qty,
      base_amount:      amount,
      discount_amount:  0,
      fingerprint:      0.00,           // الأدمن يحدد المبلغ بالكامل — لا قرش قائد
      expected_amount:  amount,
      status:           'scanning',     // ينتقل مباشرة لـ scanning
      sender_phone:     cleanPhone,
      sender_name:      cleanName,
      idempotency_key:  idempotencyKey,
      expires_at:       expiresAt,
      metadata: {
        is_test_order:    true,
        created_by_admin: adminId,
        note:             note ?? null,
      },
    })
    .select('id, order_number, expected_amount, credits_qty, expires_at, status')
    .single();

  if (insertErr || !order) {
    console.error('[admin-test-order] payment_orders insert error:', insertErr?.message);
    return errorResponse('تعذر إنشاء الطلب: ' + (insertErr?.message ?? 'خطأ غير معروف'), 500);
  }

  // ── 2. جلب رقم فودافون كاش من الإعدادات ─────────────────
  const { data: settingRow } = await db
    .from('system_settings')
    .select('value')
    .eq('key', 'vodafone_cash_number')
    .maybeSingle();
  const vfNum = (settingRow as any)?.value ?? null;

  // ── 3. INSERT wallet_topup_requests (trigger يُرسل للجهاز) ─
  const { data: topup, error: topupErr } = await db
    .from('wallet_topup_requests')
    .insert({
      customer_id:         customer_id,
      amount:              amount,
      credits_requested:   qty,
      fingerprint_amount:  amount,
      sender_phone:        cleanPhone,
      sender_name:         cleanName,
      payment_method:      'vodafone_cash',
      package_id:          null,
      notes:               `admin_test|payment_order_id:${order.id}|${note ?? ''}`.trim(),
    })
    .select('id')
    .single();

  if (topupErr || !topup) {
    // نتراجع: نلغي الـ payment_order
    await db.from('payment_orders')
      .update({ status: 'cancelled', cancelled_at: new Date().toISOString() })
      .eq('id', order.id);
    console.error('[admin-test-order] wallet_topup_requests insert error:', topupErr?.message);
    return errorResponse('تعذر إرسال الطلب للجهاز: ' + (topupErr?.message ?? 'خطأ غير معروف'), 500);
  }

  // ── 4. تسجيل في admin_audit_log ──────────────────────────
  await db.from('admin_audit_log').insert({
    admin_id:    adminId,
    action:      'create_test_order',
    target_type: 'payment_order',
    target_id:   order.id,
    details: {
      customer_id,
      credits_qty:      qty,
      exact_amount:     amount,
      sender_phone:     cleanPhone,
      topup_request_id: topup.id,
      note:             note ?? null,
    },
  });

  return jsonResponse({
    ok:               true,
    order_id:         order.id,
    order_number:     order.order_number,
    expected_amount:  order.expected_amount,
    credits_qty:      order.credits_qty,
    expires_at:       order.expires_at,
    topup_request_id: topup.id,
    sender_phone:     cleanPhone,
    sender_name:      cleanName,
    customer_name:    customerProfile.full_name ?? null,
    customer_email:   customerProfile.email ?? null,
    vodafone_number:  vfNum,
  });
});
