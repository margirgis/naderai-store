import { createClient } from 'npm:@supabase/supabase-js@2';
import { handleCors, jsonResponse, errorResponse } from '../_shared/cors.ts';

// ── Wallet-Auto-Confirm v3 ──────────────────────────────────────────────────
// Handles: device_register | heartbeat | task_result | test_ping | command_ack | legacy SMS webhook

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
  android_version?: string;
  app_version?: string;
  phone_number?: string;
  capabilities?: Record<string, unknown>;
  [key: string]: unknown;
}

interface TaskResultPayload {
  action: 'task_result';
  device_id: string;
  task_id: string;
  idempotency_key?: string;
  status: 'success' | 'failure' | 'not_found' | 'amount_mismatch' | 'duplicate';
  result_data?: {
    sender_phone?: string;
    sender_name?: string;
    amount?: number;
    transaction_id?: string;
    receiver_wallet?: string;
    transaction_time?: string;
    sms_body?: string;
    scanned_at?: string;
  };
  failure_reason?: string;
  [key: string]: unknown;
}

interface TestPingPayload {
  action: 'test_ping';
  device_id: string;
  app_version?: string;
  device_model?: string;
  android_version?: string;
  sent_at?: string;
  [key: string]: unknown;
}

interface DeviceRegisterPayload {
  action: 'device_register';
  device_id: string;
  device_model?: string;
  device_name?: string;
  android_version?: string;
  app_version?: string;
  phone_number?: string;
  capabilities?: Record<string, unknown>;
  [key: string]: unknown;
}

interface CommandAckPayload {
  action: 'command_ack';
  device_id: string;
  command_id: string;
  response_data?: Record<string, unknown>;
  sent_at?: string;
  [key: string]: unknown;
}

function isHeartbeat(payload: Record<string, unknown>): payload is HeartbeatPayload {
  return payload.action === 'heartbeat' && typeof payload.device_id === 'string';
}

function isTaskResult(payload: Record<string, unknown>): payload is TaskResultPayload {
  return payload.action === 'task_result' && typeof payload.device_id === 'string' && typeof payload.task_id === 'string';
}

function isTestPing(payload: Record<string, unknown>): payload is TestPingPayload {
  return payload.action === 'test_ping' && typeof payload.device_id === 'string';
}

function isDeviceRegister(payload: Record<string, unknown>): payload is DeviceRegisterPayload {
  return payload.action === 'device_register' && typeof payload.device_id === 'string';
}

function isCommandAck(payload: Record<string, unknown>): payload is CommandAckPayload {
  return payload.action === 'command_ack' && typeof payload.device_id === 'string' && typeof payload.command_id === 'string';
}

Deno.serve(async (req: Request) => {
  const cors = handleCors(req);
  if (cors) return cors;
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  const rawSecret = req.headers.get('X-SMS-Webhook-Secret') ?? req.headers.get('Authorization');
  const expected = Deno.env.get('SMS_WEBHOOK_SECRET') ?? Deno.env.get('WEBHOOK_SECRET');
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

  // ── Device Register ───────────────────────────────────────────────────────
  if (isDeviceRegister(payload)) {
    const { data, error } = await db.rpc('register_device', {
      p_device_id: payload.device_id,
      p_device_model: payload.device_model ?? null,
      p_device_name: payload.device_name ?? null,
      p_android_version: payload.android_version ?? null,
      p_app_version: payload.app_version ?? null,
      p_phone_number: payload.phone_number ?? null,
      p_capabilities: payload.capabilities ?? {},
    });
    if (error) return errorResponse(`DEVICE_REGISTRATION_FAILED: ${error.message}`, 500);
    return jsonResponse({ ok: true, action: 'device_register', ...data });
  }

  // ── Command Ack ───────────────────────────────────────────────────────────
  if (isCommandAck(payload)) {
    const sentAt = payload.sent_at ? new Date(payload.sent_at as string).getTime() : null;
    const responseTimeMs = sentAt ? Date.now() - sentAt : null;
    const responseData = {
      ...(payload.response_data ?? {}),
      response_time_ms: responseTimeMs,
      acked_at: new Date().toISOString(),
    };
    const { error } = await db.rpc('ack_device_command', {
      p_command_id: payload.command_id,
      p_device_id: payload.device_id,
      p_response_data: responseData,
    });
    if (error) return errorResponse(`COMMAND_ACK_FAILED: ${error.message}`, 500);
    return jsonResponse({ ok: true, action: 'command_ack', response_time_ms: responseTimeMs });
  }

  // ── Test Ping ────────────────────────────────────────────────────────────
  if (isTestPing(payload)) {
    const receivedAt = new Date().toISOString();
    const sentAt = payload.sent_at ? new Date(payload.sent_at as string).getTime() : null;
    const responseTimeMs = sentAt ? Date.now() - sentAt : null;

    await db.from('sms_device_status').upsert({
      device_id: payload.device_id,
      device_model: payload.device_model ?? null,
      android_version: payload.android_version ?? null,
      app_version: payload.app_version ?? null,
      last_heartbeat_at: receivedAt,
      last_test_at: receivedAt,
      last_test_result: 'success',
      response_time_ms: responseTimeMs,
      status: 'online',
      is_active: true,
    }, { onConflict: 'device_id' });

    // Notify admin
    await db.rpc('create_admin_notification', {
      p_title: 'اختبار اتصال ناجح من Android ✓',
      p_message: `الجهاز ${(payload.device_model as string ?? payload.device_id)} متصل — زمن الاستجابة: ${responseTimeMs ?? '?'}ms`,
      p_event_type: 'test_ping_success',
      p_reference_id: payload.device_id,
      p_device_id: payload.device_id,
    });

    return jsonResponse({
      ok: true,
      action: 'test_ping',
      received_at: receivedAt,
      response_time_ms: responseTimeMs,
      server_status: 'online',
    });
  }

  // ── Heartbeat ─────────────────────────────────────────────────────────────
  if (isHeartbeat(payload)) {
    await db.from('sms_device_status').upsert({
      device_id: payload.device_id,
      device_model: payload.device_model ?? null,
      device_name: payload.device_name ?? null,
      android_version: payload.android_version ?? null,
      app_version: payload.app_version ?? null,
      phone_number: payload.phone_number ?? null,
      capabilities: payload.capabilities ?? {},
      last_heartbeat_at: new Date().toISOString(),
      status: 'online',
      is_active: true,
    }, { onConflict: 'device_id' });

    // Re-dispatch any pending topup requests that have no active task
    // (created while no device was online, or after reconnection)
    const { data: retryResult } = await db.rpc('retry_pending_topup_requests', {
      p_device_id: payload.device_id,
    });
    const newlyDispatched = (retryResult as any)?.dispatched ?? 0;

    const { data: result } = await db.rpc('get_device_pending_tasks', {
      p_device_id: payload.device_id,
    });

    // result is {tasks: [...], commands: [...]}
    const tasks = (result as any)?.tasks ?? [];
    const commands = (result as any)?.commands ?? [];

    // Notify admin when new tasks are dispatched to device on this heartbeat
    if (newlyDispatched > 0) {
      await db.rpc('create_admin_notification', {
        p_title: `تم إرسال ${newlyDispatched} طلب للجهاز 📱`,
        p_message: `الجهاز ${payload.device_model ?? payload.device_id.slice(0,12)} استلم ${newlyDispatched} مهمة جديدة`,
        p_event_type: 'order_dispatched',
        p_reference_id: payload.device_id,
        p_device_id: payload.device_id,
      });
    }

    return jsonResponse({
      ok: true,
      action: 'heartbeat',
      status: 'online',
      pending_tasks: tasks,
      commands,
      newly_dispatched: newlyDispatched,
    });
  }

  // ── Task Result ───────────────────────────────────────────────────────────
  if (isTaskResult(payload)) {
    // Idempotency: check if this task_id was already completed
    if (payload.idempotency_key) {
      const { data: existing } = await db
        .from('pending_tasks')
        .select('task_status, result_status, result_data')
        .eq('id', payload.task_id)
        .single();
      if (existing?.task_status === 'completed') {
        return jsonResponse({ ok: true, idempotent: true, result_status: existing.result_status });
      }
    }
    const { data, error } = await db.rpc('complete_device_task', {
      p_task_id: payload.task_id,
      p_status: payload.status,
      p_result_data: payload.result_data ?? null,
      p_failure_reason: payload.failure_reason ?? null,
    });
    if (error) return errorResponse(`ORDER_DELIVERY_FAILED: ${error.message}`, 500);

    // Notify admin of scan result
    const statusLabels: Record<string, string> = {
      success: 'تم العثور على العملية ✓',
      not_found: 'لم يتم العثور',
      failure: 'فشل الفحص',
      amount_mismatch: 'مبلغ غير مطابق',
      duplicate: 'عملية مكررة',
    };
    await db.rpc('create_admin_notification', {
      p_title: statusLabels[payload.status] ?? `نتيجة الفحص: ${payload.status}`,
      p_message: `المهمة ${payload.task_id.slice(0,8)} — ${payload.failure_reason ?? (payload.result_data as any)?.transaction_id ?? ''}`,
      p_event_type: `scan_${payload.status}`,
      p_reference_id: payload.task_id,
      p_device_id: payload.device_id,
    });

    return jsonResponse(data);
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
