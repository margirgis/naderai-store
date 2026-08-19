/**
 * check-transaction-id — يتحقق من رقم العملية قبل إرسال الطلب
 * POST { transaction_id: string }
 *
 * يرجع:
 *   { ok: true,  duplicate: false }  → رقم العملية جديد — يمكن المتابعة
 *   { ok: true,  duplicate: true,  order_number, confirmed_at } → مكرر — يجب الرفض
 *   { ok: false, error }             → خطأ في الطلب
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // ── Auth ─────────────────────────────────────────────────────────────────
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

  // ── Parse body ───────────────────────────────────────────────────────────
  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const transaction_id = typeof body.transaction_id === 'string'
    ? body.transaction_id.trim() : null;

  if (!transaction_id || transaction_id.length < 4) {
    return jsonResponse({ ok: true, duplicate: false, reason: 'too_short' });
  }

  // ── Check confirmed_transactions (authoritative) ──────────────────────────
  const { data: ct } = await db
    .from('confirmed_transactions')
    .select('transaction_id, order_id, confirmed_at')
    .eq('transaction_id', transaction_id)
    .maybeSingle();

  if (ct) {
    // Get order_number for display
    const { data: topup } = await db
      .from('wallet_topup_requests')
      .select('order_number')
      .eq('id', ct.order_id)
      .maybeSingle();

    // Log suspicious activity: same user trying to reuse a confirmed tx
    await db.from('security_audit_log').insert({
      event_type: 'duplicate_tx_precheck',
      user_id: user.id,
      details: {
        transaction_id,
        confirmed_order_id: ct.order_id,
        confirmed_at: ct.confirmed_at,
      },
    }).catch(() => {});

    return jsonResponse({
      ok: true,
      duplicate: true,
      transaction_id,
      confirmed_at: ct.confirmed_at,
      order_number: topup?.order_number ?? null,
      message: `رقم العملية ${transaction_id} تم استخدامه مسبقاً في طلب #${topup?.order_number ?? '?'}`,
    });
  }

  // ── Also check wallet_topup_requests.transaction_id (belt-and-suspenders) ─
  const { data: wtr } = await db
    .from('wallet_topup_requests')
    .select('order_number, status, transaction_id')
    .eq('transaction_id', transaction_id)
    .in('status', ['approved', 'confirmed'])
    .maybeSingle();

  if (wtr) {
    return jsonResponse({
      ok: true,
      duplicate: true,
      transaction_id,
      order_number: wtr.order_number,
      message: `رقم العملية ${transaction_id} تم استخدامه مسبقاً في طلب #${wtr.order_number}`,
    });
  }

  return jsonResponse({ ok: true, duplicate: false });
});
