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
    /(?:\u0627\u0644\u0645\u0633\u062c\u0644\s+)?\u0628\u0625\u0633\u0645\s+([\u0600-\u06FF ]{2,40})\s*\u0639\u0644\u0649/,
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
    const reassignedFromOffline = (retryResult as any)?.reassigned_from_offline ?? 0;
    const reopenedExpired = (retryResult as any)?.reopened_expired ?? 0;


    const { data: result } = await db.rpc('get_device_pending_tasks', {
      p_device_id: payload.device_id,
    });

    // Diagnostics: log the raw response shape without sensitive data
    const resultKeys = result ? Object.keys(result as any).join(',') : '<null>';
    console.log(`[heartbeat] device_id=${payload.device_id} get_device_pending_tasks keys=[${resultKeys}]`);

    // Migration 00040 changed the RPC key to 'pending_tasks'; support both old and new shapes.
    const resultObj = (result as any) ?? {};
    const tasks = resultObj.pending_tasks ?? resultObj.tasks ?? [];
    const commands = resultObj.commands ?? [];
    console.log(`[heartbeat] tasks returned=${tasks.length} commands=${commands.length} device_id=${payload.device_id}`);

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

    if (reassignedFromOffline > 0) {
      await db.rpc('create_admin_notification', {
        p_title: `إعادة توزيع ${reassignedFromOffline} طلب من جهاز غير متصل 🔄`,
        p_message: `تمت إعادة توزيع ${reassignedFromOffline} مهمة من جهاز مقفل/غير متصل إلى الجهاز الحالي`,
        p_event_type: 'stale_device_reassigned',
        p_reference_id: payload.device_id,
        p_device_id: payload.device_id,
      });
    }

    if (reopenedExpired > 0) {
      await db.rpc('create_admin_notification', {
        p_title: `إعادة فتح ${reopenedExpired} طلب منتهي الصلاحية 🔄`,
        p_message: `تم إعادة فتح ${reopenedExpired} طلب لإعادة الفحص بعد رجوع الجهاز للاتصال`,
        p_event_type: 'expired_reopened',
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
      reassigned_from_offline: reassignedFromOffline,
      reopened_expired: reopenedExpired,
      min_version_code: 52,
    });
  }

  // ── Task Result ───────────────────────────────────────────────────────────
  if (isTaskResult(payload)) {
    console.log(`[TRANSACTION_CHECK] task_result received | task_id=${payload.task_id} device=${payload.device_id} status=${payload.status} tx=${payload.result_data?.transaction_id ?? 'none'} amount=${payload.result_data?.amount ?? 'none'}`);

    // Idempotency: check if this task_id was already completed
    if (payload.idempotency_key) {
      const { data: existing } = await db
        .from('pending_tasks')
        .select('task_status, result_status, result_data, payment_order_id, order_expires_at')
        .eq('id', payload.task_id)
        .single();
      if (existing?.task_status === 'completed') {
        console.log(`[TRANSACTION_ALREADY_USED] idempotent retry task_id=${payload.task_id} result_status=${existing.result_status}`);
        return jsonResponse({ ok: true, idempotent: true, result_status: existing.result_status });
      }
    }

    // ── SMS time-window check against payment_order.expires_at ──
    const { data: taskRow } = await db
      .from('pending_tasks')
      .select('payment_order_id, order_expires_at, amount_requested')
      .eq('id', payload.task_id)
      .maybeSingle();

    console.log(`[ORDER_FOUND] task_id=${payload.task_id} payment_order_id=${taskRow?.payment_order_id ?? 'none'} expires=${taskRow?.order_expires_at ?? 'none'}`);

    if (taskRow?.order_expires_at) {
      const expiresAt = new Date(taskRow.order_expires_at as string).getTime();
      if (Date.now() > expiresAt) {
        console.log(`[ORDER_EXPIRED] task_id=${payload.task_id} expires_at=${taskRow.order_expires_at}`);
        await db.rpc('complete_device_task', {
          p_task_id: payload.task_id,
          p_status: 'failure',
          p_result_data: payload.result_data ?? null,
          p_failure_reason: 'انتهت صلاحية الطلب قبل وصول نتيجة الفحص',
          p_idempotency_key: payload.idempotency_key ?? null,
        });
        return jsonResponse({ ok: false, reason: 'order_expired' });
      }
    }

    if (payload.status === 'success' && payload.result_data?.transaction_id) {
      console.log(`[SMS_FOUND] task_id=${payload.task_id} tx=${payload.result_data.transaction_id} amount=${payload.result_data.amount} sender=${payload.result_data.sender_phone ?? 'unknown'}`);
    } else if (payload.status === 'not_found') {
      console.log(`[SMS_NOT_FOUND] task_id=${payload.task_id} device=${payload.device_id}`);
    } else if (payload.status === 'amount_mismatch') {
      console.log(`[AMOUNT_MISMATCH] task_id=${payload.task_id} amount=${payload.result_data?.amount} reason=${payload.failure_reason ?? ''}`);
    } else if (payload.status === 'duplicate') {
      console.log(`[TRANSACTION_ALREADY_USED] task_id=${payload.task_id} tx=${payload.result_data?.transaction_id ?? 'unknown'}`);
    } else if (payload.status === 'failure') {
      console.log(`[SCAN_FAILURE] task_id=${payload.task_id} reason=${payload.failure_reason ?? 'unknown'}`);
    }

    // complete_device_task handles both payment_orders and legacy topup requests
    const { data, error } = await db.rpc('complete_device_task', {
      p_task_id: payload.task_id,
      p_status: payload.status,
      p_result_data: payload.result_data ?? null,
      p_failure_reason: payload.failure_reason ?? null,
      p_idempotency_key: payload.idempotency_key ?? null,
    });
    if (error) {
      console.error(`[SCAN_FAILURE] complete_device_task RPC error task_id=${payload.task_id}: ${error.message}`);
      return errorResponse(`ORDER_DELIVERY_FAILED: ${error.message}`, 500);
    }

    const result = (data ?? {}) as Record<string, unknown>;
    const scanStatus = typeof result.scan_status === 'string' ? result.scan_status : payload.status;

    if (result.ok === true && scanStatus === 'confirmed') {
      console.log(`[PAYMENT_CONFIRMED] task_id=${payload.task_id} order_id=${taskRow?.payment_order_id} tx=${payload.result_data?.transaction_id} credits=${result.credits_added ?? 'unknown'}`);
    } else if (result.ok === false) {
      console.log(`[ORDER_UPDATED] task_id=${payload.task_id} scan_status=${scanStatus} reason=${result.reason ?? 'unknown'}`);
    }

    // Notify admin of scan result
    const statusLabels: Record<string, string> = {
      success:        'تم العثور على العملية ✓',
      not_found:      'لم يتم العثور',
      failure:        'فشل الفحص',
      amount_mismatch:'مبلغ غير مطابق',
      duplicate:      'عملية مكررة',
    };
    const referenceId = (taskRow?.payment_order_id as string) ?? payload.task_id;
    await db.rpc('create_admin_notification', {
      p_title: statusLabels[payload.status] ?? `نتيجة الفحص: ${scanStatus}`,
      p_message: `المهمة ${payload.task_id.slice(0, 8)} — ${payload.failure_reason ?? result.transaction_id ?? ''}`,
      p_event_type: `scan_${scanStatus}`,
      p_reference_id: referenceId,
      p_device_id: payload.device_id,
    });

    console.log(`[ORDER_UPDATED] FINAL task_id=${payload.task_id} scan_status=${scanStatus} ok=${result.ok}`);
    return jsonResponse({ ok: true, scan_status: scanStatus, ...result });
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
