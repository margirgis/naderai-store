/**
 * admin-manual-confirm v2
 * POST { order_id, reason?, topup_request_id?, transaction_id?, received_amount? }
 *
 * Routes through atomic_process_payment for full ledger integrity:
 * - Duplicate transaction_id is rejected at DB level (UNIQUE constraint)
 * - Credit is added atomically (no double-credit possible)
 * - Every action logged to financial_audit_log
 *
 * Falls back to admin_manual_confirm_order RPC when no transaction_id provided
 * (manual override without SMS verification).
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

  let body: {
    order_id?: string;
    reason?: string;
    topup_request_id?: string;
    transaction_id?: string;
    received_amount?: number;
    sender_phone?: string;
  };
  try { body = await req.json(); } catch { return errorResponse('طلب غير صحيح', 400); }

  const { order_id, reason, topup_request_id, transaction_id, received_amount, sender_phone } = body;
  if (!order_id) return errorResponse('order_id مطلوب', 400);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const actor = `admin:${adminId}`;

  // ── Path A: Full transaction provided → use atomic_process_payment ────────
  if (transaction_id && received_amount) {
    const { data, error } = await db.rpc('atomic_process_payment', {
      p_order_id:        order_id,
      p_transaction_id:  transaction_id,
      p_received_amount: received_amount,
      p_sender_phone:    sender_phone ?? null,
      p_sender_name:     null,
      p_device_id:       null,
      p_sms_body:        null,
      p_actor:           actor,
      p_topup_id:        topup_request_id ?? null,
    });
    if (error) return errorResponse('خطأ في التأكيد: ' + error.message, 500);
    const result = data as Record<string, unknown>;
    if (!result.ok) {
      const status = result.reason === 'duplicate_transaction_id' ? 409 : 400;
      return jsonResponse(result, status);
    }
    return jsonResponse(result);
  }

  // ── Path B: Manual override (no transaction) → admin_manual_confirm_order ─
  // Generates a synthetic MANUAL-<uuid> transaction_id to prevent double-credit
  const { data, error } = await db.rpc('admin_manual_confirm_order', {
    p_order_id:  order_id,
    p_admin_id:  adminId,
    p_reason:    reason ?? 'تأكيد يدوي من لوحة الأدمن',
    p_topup_id:  topup_request_id ?? null,
  });

  if (error) return errorResponse('خطأ في التأكيد: ' + error.message, 500);
  const result = data as Record<string, unknown> | null;
  if (!result?.ok) return errorResponse((result?.reason as string) ?? 'فشل التأكيد', 400);

  return jsonResponse(result);
});
