/**
 * process-payment — Edge Function
 * POST { order_id, transaction_id, received_amount, sender_phone?, sender_name?, device_id?, sms_body?, topup_request_id? }
 *
 * Routes through atomic_process_payment RPC:
 *   1. Check transaction_id uniqueness (UNIQUE constraint in DB)
 *   2. Lock order row FOR UPDATE
 *   3. Validate order not terminal/expired
 *   4. Validate EXACT amount match
 *   5. Validate sender_phone contract (if set)
 *   6. Confirm order → status='confirmed', add credit atomically
 *   7. Insert payment_transactions record + log to financial_audit_log
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // Auth: accept webhook secret (device) OR admin JWT
  const authHeader = req.headers.get('Authorization') ?? '';
  const webhookSecret = req.headers.get('X-SMS-Webhook-Secret') ?? '';
  const expectedSecret = Deno.env.get('SMS_WEBHOOK_SECRET') ?? Deno.env.get('WEBHOOK_SECRET') ?? '';

  const isDevice = webhookSecret === expectedSecret && expectedSecret !== '';
  const isAdmin  = authHeader.startsWith('Bearer ') && !isDevice;

  if (!isDevice && !isAdmin) {
    return errorResponse('Unauthorized', 401);
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return errorResponse('طلب JSON غير صحيح', 400); }

  const {
    order_id,
    transaction_id,
    received_amount,
    sender_phone,
    sender_name,
    device_id,
    sms_body,
    topup_request_id,
  } = body as {
    order_id?: string;
    transaction_id?: string;
    received_amount?: number;
    sender_phone?: string;
    sender_name?: string;
    device_id?: string;
    sms_body?: string;
    topup_request_id?: string;
  };

  if (!order_id)       return errorResponse('order_id مطلوب', 400);
  if (!received_amount) return errorResponse('received_amount مطلوب', 400);
  if (!transaction_id)  return errorResponse('transaction_id مطلوب', 400);

  // Determine actor
  let actor = isDevice ? `device:${device_id ?? 'unknown'}` : 'admin:api';
  if (isAdmin) {
    // Extract admin user_id from JWT
    try {
      const userClient = createClient(
        Deno.env.get('SUPABASE_URL')!,
        Deno.env.get('SUPABASE_ANON_KEY')!,
        { global: { headers: { Authorization: authHeader } } },
      );
      const { data: { user } } = await userClient.auth.getUser();
      if (user) actor = `admin:${user.id}`;
    } catch { /* keep default */ }
  }

  const { data, error } = await db.rpc('atomic_process_payment', {
    p_order_id:        order_id,
    p_transaction_id:  transaction_id,
    p_received_amount: received_amount,
    p_sender_phone:    sender_phone   ?? null,
    p_sender_name:     sender_name    ?? null,
    p_device_id:       device_id      ?? null,
    p_sms_body:        sms_body       ?? null,
    p_actor:           actor,
    p_topup_id:        topup_request_id ?? null,
  });

  if (error) {
    console.error('[process-payment] RPC error:', error.message);
    return errorResponse('خطأ في معالجة الدفعة: ' + error.message, 500);
  }

  const result = data as Record<string, unknown>;

  // Send appropriate HTTP status
  if (!result.ok) {
    const status = result.reason === 'duplicate_transaction_id' ? 409 : 400;
    return jsonResponse(result, status);
  }

  return jsonResponse(result);
});
