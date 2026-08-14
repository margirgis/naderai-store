import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

interface SmsPayload {
  sender_phone?: string;
  sender_name?: string;
  amount?: number | string;
  transaction_id?: string;
  message?: string;
  received_at?: string;
  device_id?: string;
}

function normalizeEgyptianPhone(raw: string): string | null {
  const digits = raw.replace(/\D/g, '');
  if (digits.length === 10 && digits.startsWith('1')) return digits;
  if (digits.length === 11 && digits.startsWith('01')) return digits.slice(1);
  if (digits.length === 12 && digits.startsWith('20') && digits[2] === '1') return digits.slice(2);
  if (digits.length === 13 && digits.startsWith('20') && digits[3] === '1') return digits.slice(3);
  return null;
}

function extractAmount(text: string): number | null {
  const patterns = [
    /مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه/,
    /استلمت\s+(?:من\s+.+?\s+)?مبلغ\s*([\d,]+\.?\d{0,2})/,
    /received\s+(?:egp\s+)?([\d,]+\.?\d{0,2})/i,
    /egp\s+([\d,]+\.?\d{0,2})/i,
    /([\d,]+\.\d{1,2})\s*جنيه/,
    /([\d,]+\.?\d{0,2})/,
  ];
  for (const re of patterns) {
    const m = text.match(re);
    if (m) {
      const n = parseFloat(m[1].replace(/,/g, ''));
      if (!isNaN(n) && n > 0) return n;
    }
  }
  return null;
}

function extractTransactionId(text: string): string | null {
  const patterns = [
    /كود المعاملة[:\s]+([A-Za-z0-9]+)/,
    /transaction\s*id[:\s]+([A-Za-z0-9]+)/i,
    /رقم العملية[:\s]+([A-Za-z0-9]+)/,
    /\b([A-Z]{2,}[0-9]{4,})\b/,
  ];
  for (const re of patterns) {
    const m = text.match(re);
    if (m) return m[1];
  }
  return null;
}

function extractSenderName(text: string): string | null {
  const patterns = [
    /\u0628\u0625\u0633\u0645\s+([A-Za-z][A-Za-z0-9 ]{1,30})\s*\u0639\u0644\u0649/,
    /\u0628\u0627\u0633\u0645\s+([\u0600-\u06FF ]{2,30})\s+/,
    /from\s+([A-Za-z][A-Za-z ]{1,30})\s+on/i,
  ];
  for (const re of patterns) {
    const m = text.match(re);
    if (m) {
      const candidate = m[1].trim();
      if (!/^\d+$/.test(candidate)) return candidate;
    }
  }
  return null;
}

interface HeartbeatPayload {
  action: 'heartbeat';
  device_id: string;
  device_model?: string;
  device_name?: string;
  app_version?: string;
  [key: string]: unknown;
}

function isHeartbeat(payload: Record<string, unknown>): payload is HeartbeatPayload {
  return payload.action === 'heartbeat' && typeof payload.device_id === 'string';
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  const rawSecret = req.headers.get('X-SMS-Webhook-Secret') ?? req.headers.get('Authorization');
  const expected = Deno.env.get('SMS_WEBHOOK_SECRET');
  const secret = rawSecret?.replace(/^Bearer\s+/i, '') ?? '';
  if (!expected || secret !== expected) return errorResponse('Invalid secret', 401);

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  // Heartbeat from Android device
  if (isHeartbeat(payload)) {
    await db.from('sms_device_status').upsert({
      device_id: payload.device_id,
      device_model: payload.device_model ?? null,
      device_name: payload.device_name ?? null,
      app_version: payload.app_version ?? null,
      last_heartbeat_at: new Date().toISOString(),
      status: 'online',
      is_active: true,
    }, { onConflict: 'device_id' });
    return jsonResponse({ ok: true, action: 'heartbeat', status: 'online' });
  }

  const smsPayload = payload as SmsPayload;
  const message = (smsPayload.message ?? '').trim();

  // Normalize phone
  let senderPhone = normalizeEgyptianPhone(smsPayload.sender_phone ?? '');
  if (!senderPhone && message) {
    const m = message.match(/(?:\+?20)?\s*0?1\d{9}/);
    if (m) senderPhone = normalizeEgyptianPhone(m[0]);
  }

  // Parse amount (support both numeric and string from Android)
  let amount: number | null = null;
  if (smsPayload.amount) {
    const raw = typeof smsPayload.amount === 'number' ? smsPayload.amount : parseFloat(String(smsPayload.amount));
    if (!isNaN(raw) && raw > 0) amount = raw;
  }
  if (!amount && message) amount = extractAmount(message);

  // Extract optional fields
  const senderName = smsPayload.sender_name?.trim() || (message ? extractSenderName(message) : null);
  const transactionId = smsPayload.transaction_id?.trim() || (message ? extractTransactionId(message) : null);

  if (!senderPhone || !amount) {
    return jsonResponse({ ok: false, reason: 'Could not parse sender_phone or amount', sender_phone: senderPhone, amount });
  }

  // Update last webhook timestamp for any known device (best effort)
  if (smsPayload.device_id) {
    await db.from('sms_device_status').upsert({
      device_id: smsPayload.device_id,
      last_webhook_at: new Date().toISOString(),
    }, { onConflict: 'device_id' });
  }

  const { data, error } = await db.rpc('auto_confirm_wallet_topup', {
    p_sender_phone: senderPhone,
    p_amount: amount,
    p_sender_name: senderName ?? null,
    p_transaction_id: transactionId ?? null,
    p_sms_body: message || null,
  });

  if (error) return errorResponse(error.message, 500);

  return jsonResponse(data);
});
