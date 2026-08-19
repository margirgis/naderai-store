/**
 * admin-open-case
 * POST { order_id, reason, notes? }
 * → calls open_support_case RPC
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

  let body: { order_id?: string; reason?: string; notes?: string };
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const { order_id, reason, notes } = body;
  if (!order_id) return errorResponse('order_id مطلوب', 400);
  if (!reason?.trim()) return errorResponse('reason مطلوب', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data, error } = await db.rpc('open_support_case', {
    p_order_id:  order_id,
    p_admin_id:  adminId,
    p_reason:    reason.trim(),
    p_notes:     notes?.trim() ?? null,
  });

  if (error) return errorResponse('خطأ في فتح القضية: ' + error.message, 500);
  if (!data?.ok) return errorResponse(data?.reason ?? 'فشل فتح القضية', 400);

  return jsonResponse(data);
});
