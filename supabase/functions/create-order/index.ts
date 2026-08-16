/**
 * create-order — Live provider order creation
 * POST { service_id, quantity? }
 * Security: API Key server-side only. Price/code from DB. No client trust.
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAuth } from '../_shared/auth-user.ts';
import { providerCall } from '../_shared/provider-client.ts';

/* ── Safe error → Arabic customer message ──────────────────────────── */
function safeCustomerMessage(code?: string, httpStatus?: number): string {
  if (code === 'INSUFFICIENT_BALANCE' || code === 'LOW_BALANCE') {
    return 'لا يمكن تنفيذ الطلب حاليًا بسبب عدم كفاية رصيد المزود.';
  }
  if (code === 'SERVICE_UNAVAILABLE' || code === 'MAINTENANCE') {
    return 'الخدمة غير متاحة حاليًا.';
  }
  if (code === 'VALIDATION_ERROR' || httpStatus === 422) {
    return 'بيانات الطلب غير صحيحة.';
  }
  if (code === 'TIMEOUT' || code === 'NETWORK_ERROR') {
    return 'انتهت مهلة الاتصال بالمزود. سيتم التحقق من حالة الطلب تلقائيًا.';
  }
  return 'تعذر إنشاء الطلب حاليًا. حاول مرة أخرى بعد قليل.';
}

/* ── Push notification helper ───────────────────────────────────────── */
async function pushNotification(db: ReturnType<typeof createClient>, params: {
  userId: string; type: string; title: string; body: string; orderId?: string;
}) {
  try {
    await db.from('notifications').insert({
      user_id: params.userId,
      type: params.type,
      title: params.title,
      body: params.body,
      order_id: params.orderId ?? null,
    });
  } catch { /* notification failure must not break the order flow */ }
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // 1. Auth
  let userId: string;
  try {
    const auth = await requireAuth(req);
    userId = auth.userId;
  } catch (e) {
    return errorResponse(String(e), 401);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  // 2. Parse body
  let body: { service_id?: string; quantity?: number; idempotency_key?: string };
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const { service_id, quantity } = body;
  if (!service_id) return errorResponse('service_id مطلوب', 400);

  // 3. Load service from DB
  const { data: svc, error: svcErr } = await db
    .from('provider_services')
    .select('id, provider_code, name, display_name_ar, status, input_type, customer_price, provider_credit_price, final_credit_price, max_items_per_request, store_enabled')
    .eq('id', service_id)
    .maybeSingle();

  if (svcErr || !svc) return errorResponse('الخدمة غير موجودة', 404);
  if (!svc.store_enabled) return errorResponse('هذه الخدمة غير متاحة في المتجر حالياً', 400);
  if (svc.status !== 'active') return errorResponse('الخدمة تحت الصيانة أو غير نشطة', 400);

  // 4. For extract_18m (quantity service): always quantity=1
  const inputType = svc.input_type ?? 'quantity';
  const resolvedQuantity = inputType === 'quantity' ? (quantity ?? 1) : 1;
  if (resolvedQuantity < 1) return errorResponse('الكمية يجب أن تكون أكبر من صفر', 400);

  // 5. Compute price server-side
  const unitPrice = svc.customer_price ?? svc.final_credit_price ?? svc.provider_credit_price ?? 0;
  const customerTotal = unitPrice * resolvedQuantity;
  const providerCost = (svc.final_credit_price ?? svc.provider_credit_price ?? unitPrice) * resolvedQuantity;

  // 6. Load customer profile + wallet
  const { data: profile, error: profileErr } = await db
    .from('profiles')
    .select('wallet_balance, status')
    .eq('id', userId)
    .maybeSingle();

  if (profileErr || !profile) return errorResponse('حساب العميل غير موجود', 404);
  if (profile.status !== 'active') return errorResponse('حسابك موقوف. تواصل مع الدعم.', 403);
  if ((profile.wallet_balance ?? 0) < customerTotal) {
    return jsonResponse({ success: false, safe_message: 'رصيد غير كافٍ. تواصل مع الدعم لشحن المحفظة.' }, 402);
  }

  // 6b. Idempotency check — return existing order if same key already used
  const idempotencyKey = body.idempotency_key ?? null;
  if (idempotencyKey) {
    const { data: existing } = await db
      .from('orders')
      .select('id, status, reference, offer_link')
      .eq('customer_id', userId)
      .eq('idempotency_key', idempotencyKey)
      .maybeSingle();
    if (existing) {
      return jsonResponse({
        success: true,
        order_id: existing.id,
        reference: existing.reference,
        status: existing.status,
        offer_link: existing.offer_link ?? null,
        idempotent_replay: true,
      });
    }
  }

  // 7. Create local order (creating) — idempotency_key stored atomically
  const { data: order, error: orderErr } = await db
    .from('orders')
    .insert({
      customer_id: userId,
      service_id: svc.id,
      provider_service_code: svc.provider_code,
      quantity: resolvedQuantity,
      customer_total: customerTotal,
      provider_cost: providerCost,
      status: 'creating',
      idempotency_key: idempotencyKey,
      reference: `SHOP-PENDING-${Date.now()}`,
    })
    .select('id')
    .maybeSingle();

  if (orderErr || !order) return errorResponse('فشل إنشاء الطلب. حاول مجدداً.', 500);

  // 8. Reference update
  const reference = `SHOP-${order.id.split('-')[0].toUpperCase()}`;
  await db.from('orders').update({ reference }).eq('id', order.id);

  // 9. Deduct wallet
  const newBalance = (profile.wallet_balance ?? 0) - customerTotal;
  await db.from('profiles').update({ wallet_balance: newBalance }).eq('id', userId);
  await db.from('wallet_transactions').insert({
    customer_id: userId, type: 'debit', amount: customerTotal,
    balance_after: newBalance,
    reason: `طلب خدمة: ${svc.display_name_ar ?? svc.name}`,
    order_id: order.id, reference,
  });

  // 10. Build provider payload — quantity service uses quantity only
  const providerPayload: Record<string, unknown> = {
    service: svc.provider_code,
    quantity: resolvedQuantity,
    reference,
  };

  // 11. Send to Provider
  const providerResult = await providerCall<{
    status?: string; task_id?: string; request_id?: string;
    accepted?: number; rejected?: number; message?: string;
    offer_link?: string; two_fa_link?: string;
  }>('orders', 'POST', providerPayload);

  // 12. Map response
  let finalStatus: string;
  let providerTaskId: string | null = null;
  let providerReqId: string | null = null;
  let offerLink: string | null = null;
  let resultData: Record<string, unknown> | null = null;
  let safeErrorCode: string | null = null;
  let safeErrorMessage: string | null = null;

  if (providerResult.ok && providerResult.data) {
    const pd = providerResult.data;
    providerTaskId = pd.task_id ?? null;
    providerReqId = pd.request_id ?? providerResult.requestId ?? null;
    const accepted = pd.accepted ?? 0;
    const rejected = pd.rejected ?? 0;
    if (rejected > 0 && accepted === 0) finalStatus = 'rejected';
    else if (rejected > 0 && accepted > 0) finalStatus = 'partial';
    else finalStatus = 'queued';
    // Extract offer link if immediately available
    offerLink = pd.offer_link ?? pd.two_fa_link ?? null;
    resultData = { accepted, rejected, message: pd.message };
  } else {
    finalStatus = 'failed';
    safeErrorCode = providerResult.errorCode ?? 'PROVIDER_ERROR';
    safeErrorMessage = safeCustomerMessage(providerResult.errorCode, providerResult.httpStatus);
    // Refund on failure
    const refundBalance = newBalance + customerTotal;
    await db.from('profiles').update({ wallet_balance: refundBalance }).eq('id', userId);
    await db.from('wallet_transactions').insert({
      customer_id: userId, type: 'credit', amount: customerTotal,
      balance_after: refundBalance,
      reason: `استرداد: فشل إرسال الطلب ${reference}`,
      order_id: order.id, reference,
    });
  }

  // 13. Update order record — set offer_link_created_at (server timestamp) if link available
  const nowIso = new Date().toISOString();
  await db.from('orders').update({
    status: finalStatus,
    provider_task_id: providerTaskId,
    provider_request_id: providerReqId,
    offer_link: offerLink,
    offer_link_created_at: offerLink ? nowIso : null,
    result_data: resultData,
    safe_error_code: safeErrorCode,
    safe_error_message: safeErrorMessage,
    provider_raw_response: { http_status: providerResult.httpStatus, ok: providerResult.ok },
    updated_at: nowIso,
    completed_at: finalStatus === 'failed' ? nowIso : null,
  }).eq('id', order.id);

  // 14. Log (no secrets)
  await db.from('provider_logs').insert({
    operation: 'create_order', success: providerResult.ok,
    http_status: providerResult.httpStatus,
    provider_request_id: providerReqId,
    response_time_ms: providerResult.responseTimeMs,
    error_code: providerResult.errorCode ?? null,
    error_message: providerResult.errorMessage ? providerResult.errorMessage.slice(0, 200) : null,
  });

  // 15. Push notification
  if (finalStatus === 'failed') {
    await pushNotification(db, {
      userId, type: 'order_failed', orderId: order.id,
      title: 'تعذر تنفيذ طلبك',
      body: safeErrorMessage ?? 'تعذر تنفيذ الطلب. يرجى التواصل مع الدعم.',
    });
  } else {
    await pushNotification(db, {
      userId, type: 'order_created', orderId: order.id,
      title: 'تم إنشاء طلبك 📦',
      body: `طلبك ${reference} قيد المعالجة.`,
    });
  }

  return jsonResponse({
    success: finalStatus !== 'failed',
    order_id: order.id,
    reference,
    status: finalStatus,
    provider_task_id: providerTaskId,
    result: resultData,
    safe_message: safeErrorMessage,
  });
});
