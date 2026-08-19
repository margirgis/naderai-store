/**
 * admin-manual-confirm
 * POST { order_id, reason?, topup_request_id? }
 * → calls admin_manual_confirm_order RPC → atomic credit + full audit trail
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';
import { requireAdmin } from '../_shared/auth-guard.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  let adminId: string;
  try { adminId = await requireAdmin(req); } catch (e) { return errorResponse(String(e), 403); }

  let body: { order_id?: string; reason?: string; topup_request_id?: string };
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const { order_id, reason, topup_request_id } = body;
  if (!order_id) return errorResponse('order_id مطلوب', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data, error } = await db.rpc('admin_manual_confirm_order', {
    p_order_id:  order_id,
    p_admin_id:  adminId,
    p_reason:    reason ?? 'تأكيد يدوي من لوحة الأدمن',
    p_topup_id:  topup_request_id ?? null,
  });

  if (error) return errorResponse('خطأ في التأكيد: ' + error.message, 500);
  if (!data?.ok) return errorResponse(data?.reason ?? 'فشل التأكيد', 400);

  return jsonResponse(data);
});
