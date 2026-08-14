/**
 * admin-wallet-topup — Admin manually credits or debits a customer's wallet
 * POST { customer_id, type: 'credit'|'debit', amount, reason }
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;

  let adminId: string;
  try {
    adminId = await requireAdmin(req);
  } catch (e) {
    return errorResponse(String(e), 403);
  }

  let body: { customer_id?: string; type?: string; amount?: number; reason?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse('طلب غير صحيح', 400);
  }

  const { customer_id, type, amount, reason } = body;
  if (!customer_id || !type || !amount || !reason) {
    return errorResponse('جميع الحقول مطلوبة', 400);
  }
  if (!['credit', 'debit'].includes(type)) {
    return errorResponse('نوع العملية غير صحيح', 400);
  }
  if (amount <= 0) return errorResponse('المبلغ يجب أن يكون أكبر من صفر', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } }
  );

  const { data: profile, error: profileErr } = await db
    .from('profiles')
    .select('wallet_balance')
    .eq('id', customer_id)
    .maybeSingle();

  if (profileErr || !profile) return errorResponse('العميل غير موجود', 404);

  const currentBalance: number = profile.wallet_balance ?? 0;
  let newBalance: number;

  if (type === 'credit') {
    newBalance = currentBalance + amount;
  } else {
    if (currentBalance < amount) return errorResponse('رصيد العميل غير كافٍ للخصم', 400);
    newBalance = currentBalance - amount;
  }

  const { error: updateErr } = await db
    .from('profiles')
    .update({ wallet_balance: newBalance })
    .eq('id', customer_id);

  if (updateErr) return errorResponse('فشل تحديث الرصيد', 500);

  await db.from('wallet_transactions').insert({
    customer_id,
    type,
    amount,
    balance_after: newBalance,
    reason,
    created_by: adminId,
    reference: `ADMIN-${Date.now()}`,
  });

  // Notify customer about wallet change
  if (type === 'credit') {
    await db.from('notifications').insert({
      user_id: customer_id,
      type: 'wallet_topup',
      title: 'تم شحن رصيدك ✅',
      body: `تمت إضافة ${amount} Credit إلى محفظتك. رصيدك الآن: ${newBalance} Credit.`,
    });
  } else if (type === 'debit') {
    await db.from('notifications').insert({
      user_id: customer_id,
      type: 'wallet_debit',
      title: 'تم خصم من رصيدك ⚠️',
      body: `تم خصم ${amount} Credit من محفظتك. رصيدك الآن: ${newBalance} Credit.`,
    });
  }

  return jsonResponse({ success: true, new_balance: newBalance });
});
