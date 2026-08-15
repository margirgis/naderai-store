/**
 * admin-wallet-topup — يُعدِّل رصيد العميل بشكل آمن عبر admin_adjust_wallet RPC
 * POST { customer_id, type: 'credit'|'debit', amount, reason }
 * يُسجّل balance_before + balance_after + admin_audit_log تلقائياً من RPC
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

  // استخدام RPC الآمن الذي يُسجّل balance_before + audit_log تلقائياً
  const { data, error } = await db.rpc('admin_adjust_wallet', {
    p_admin_id: adminId,
    p_customer_id: customer_id,
    p_type: type,
    p_amount: amount,
    p_reason: reason,
    p_reference: `ADMIN-${Date.now()}`,
  });

  if (error) return errorResponse(error.message, 500);
  if (!data?.ok) {
    const reason_map: Record<string, string> = {
      unauthorized: 'غير مصرح لك بهذه العملية',
      customer_not_found: 'العميل غير موجود',
      insufficient_balance: 'رصيد العميل غير كافٍ للخصم',
      amount_must_be_positive: 'المبلغ يجب أن يكون أكبر من صفر',
      invalid_type: 'نوع العملية غير صحيح',
    };
    return errorResponse(reason_map[data?.reason] ?? data?.reason ?? 'فشلت العملية', 400);
  }

  // إشعار العميل
  if (type === 'credit') {
    await db.from('notifications').insert({
      user_id: customer_id,
      type: 'wallet_topup',
      title: 'تم شحن رصيدك ✅',
      body: `تمت إضافة ${amount} Credit إلى محفظتك. رصيدك الآن: ${data.balance_after} Credit.`,
    });
  } else {
    await db.from('notifications').insert({
      user_id: customer_id,
      type: 'wallet_debit',
      title: 'تم خصم من رصيدك ⚠️',
      body: `تم خصم ${amount} Credit من محفظتك. رصيدك الآن: ${data.balance_after} Credit.`,
    });
  }

  return jsonResponse({
    success: true,
    new_balance: data.balance_after,
    balance_before: data.balance_before,
    balance_after: data.balance_after,
  });
});
