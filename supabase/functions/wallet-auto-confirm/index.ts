import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

interface SmsPayload {
  sender_phone?: string;
  sender_name?: string;
  amount?: number | string;
  transaction_id?: string;
  message?: string;
  received_at?: string;
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
    /من\s+([\u0600-\u06FF ]{2,30})\s+مبلغ/,
    /from\s+([A-Za-z][A-Za-z ]{1,30})\s+on/i,
    /من\s+([\u0600-\u06FFa-zA-Z][\u0600-\u06FFa-zA-Z0-9 ]{1,30})\s/,
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

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  const secret = req.headers.get('X-SMS-Webhook-Secret');
  const expected = Deno.env.get('SMS_WEBHOOK_SECRET');
  if (!expected || secret !== expected) return errorResponse('Invalid secret', 401);

  let payload: SmsPayload;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  const message = (payload.message ?? '').trim();

  // Normalize phone
  let senderPhone = normalizeEgyptianPhone(payload.sender_phone ?? '');
  if (!senderPhone && message) {
    const m = message.match(/(?:\+?20)?\s*0?1\d{9}/);
    if (m) senderPhone = normalizeEgyptianPhone(m[0]);
  }

  // Parse amount (support both numeric and string from Android)
  let amount: number | null = null;
  if (payload.amount) {
    const raw = typeof payload.amount === 'number' ? payload.amount : parseFloat(String(payload.amount));
    if (!isNaN(raw) && raw > 0) amount = raw;
  }
  if (!amount && message) amount = extractAmount(message);

  // Extract optional fields
  const senderName = payload.sender_name?.trim() || (message ? extractSenderName(message) : null);
  const transactionId = payload.transaction_id?.trim() || (message ? extractTransactionId(message) : null);

  if (!senderPhone || !amount) {
    return jsonResponse({ ok: false, reason: 'Could not parse sender_phone or amount', sender_phone: senderPhone, amount });
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

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

interface SmsPayload {
  sender_phone?: string;
  amount?: number;
  message?: string;
  received_at?: string;
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
  const re = /([\d,]+\.?\d{0,2})/;
  const m = text.match(re);
  if (m) {
    const n = parseFloat(m[1].replace(/,/g, ''));
    if (!isNaN(n) && n > 0) return n;
  }
  return null;
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  const secret = req.headers.get('X-SMS-Webhook-Secret');
  const expected = Deno.env.get('SMS_WEBHOOK_SECRET');
  if (!expected || secret !== expected) return errorResponse('Invalid secret', 401);

  let payload: SmsPayload;
  try {
    payload = await req.json();
  } catch {
    return errorResponse('Invalid JSON', 400);
  }

  const message = (payload.message ?? '').trim();
  let senderPhone = normalizeEgyptianPhone(payload.sender_phone ?? '');
  let amount = payload.amount;

  if (!senderPhone && message) {
    const m = message.match(/(?:\+?20)?\s*0?1\d{9}/);
    if (m) senderPhone = normalizeEgyptianPhone(m[0]);
  }

  if (!amount && message) {
    amount = extractAmount(message);
  }

  if (!senderPhone || !amount) {
    return jsonResponse({ ok: false, reason: 'Could not parse sender_phone or amount', sender_phone: senderPhone, amount });
  }

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  const { data, error } = await db.rpc('auto_confirm_wallet_topup', {
    p_sender_phone: senderPhone,
    p_amount: amount,
  });

  if (error) {
    return errorResponse(error.message, 500);
  }

  return jsonResponse(data);
});
