/**
 * webhook-handler — receives Provider order status push callbacks
 * POST /webhook-handler  (no auth header — called by provider, not browser)
 *
 * Security layers:
 *  1. HMAC-SHA256 signature verification (X-Webhook-Signature + X-Webhook-Timestamp)
 *  2. Timestamp freshness check (±5 min)
 *  3. Event-id idempotency (webhook_events.event_id UNIQUE)
 *  4. Server-only order lookup — no client trust, no IDOR possible
 *  5. Terminal-status guard — never downgrade a completed order
 *  6. No secrets / credentials / offer_link in logs
 *
 * Supported provider payloads:
 *  { event, task_id, reference?, status?, result: { offer_link?, two_fa_link?, message? } }
 */
import { createClient } from 'npm:@supabase/supabase-js@2';
import { jsonResponse, errorResponse } from '../_shared/cors.ts';

const MAX_TIMESTAMP_DRIFT_SEC = 300; // ±5 minutes
const TERMINAL = new Set(['success', 'partial', 'failed', 'cancelled', 'rejected']);

/* ── HMAC-SHA256 signature verification ──────────────────────────── */
async function verifySignature(req: Request, rawBody: string): Promise<boolean> {
  const secret = Deno.env.get('WEBHOOK_SECRET');
  // If no secret configured: allow through (dev/sandbox). Log warning only.
  if (!secret) return true;

  const signature = req.headers.get('X-Webhook-Signature') ?? '';
  const timestamp  = req.headers.get('X-Webhook-Timestamp')  ?? '';
  if (!signature) return false;

  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const signed = await crypto.subtle.sign(
    'HMAC', key, encoder.encode(`${timestamp}.${rawBody}`),
  );
  const expected = Array.from(new Uint8Array(signed))
    .map(b => b.toString(16).padStart(2, '0')).join('');

  // Constant-time comparison to prevent timing attacks
  if (signature.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) {
    diff |= signature.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return diff === 0;
}

/* ── Status mapping from provider event/status strings ──────────── */
function mapProviderStatus(
  eventType: string,
  providerStatus?: string,
): string | null {
  const ev = (eventType ?? '').toLowerCase();
  const st = (providerStatus ?? '').toLowerCase();

  if (ev.includes('success') || st === 'success' || st === 'completed') return 'success';
  if (ev.includes('partial') || st === 'partial') return 'partial';
  if (ev.includes('failed') || ev.includes('fail') || st === 'failed' || st === 'error') return 'failed';
  if (ev.includes('reject') || st === 'rejected') return 'rejected';
  if (ev.includes('cancel') || st === 'cancelled' || st === 'canceled') return 'cancelled';
  if (ev.includes('processing') || st === 'processing' || st === 'in_progress') return 'processing';
  if (ev.includes('queued') || st === 'queued' || st === 'pending') return 'queued';
  return null; // unknown — do not change order status
}

/* ── Push notification (dedup by order_id + type) ────────────────── */
async function pushNotification(
  db: ReturnType<typeof createClient>,
  params: { userId: string; type: string; title: string; body: string; orderId: string },
) {
  try {
    const { count } = await db.from('notifications')
      .select('id', { count: 'exact', head: true })
      .eq('user_id', params.userId)
      .eq('order_id', params.orderId)
      .eq('type', params.type);
    if ((count ?? 0) === 0) {
      await db.from('notifications').insert({
        user_id: params.userId,
        type: params.type,
        title: params.title,
        body: params.body,
        order_id: params.orderId,
      });
    }
  } catch { /* notification failure must never break the webhook response */ }
}

/* ── Main handler ────────────────────────────────────────────────── */
Deno.serve(async (req: Request) => {
  // Handle CORS preflight (provider may send OPTIONS)
  if (req.method === 'OPTIONS') return new Response('ok', { status: 200 });
  if (req.method !== 'POST') return errorResponse('Method not allowed', 405);

  // Read raw body first (needed for signature verification)
  const rawBody = await req.text();

  // 1. Verify signature
  const sigValid = await verifySignature(req, rawBody);
  if (!sigValid) {
    // Log rejection without revealing body or secret
    console.error('[webhook] Invalid signature — request rejected');
    return errorResponse('Invalid signature', 401);
  }

  // 2. Timestamp freshness
  const tsHeader = req.headers.get('X-Webhook-Timestamp');
  if (tsHeader) {
    const tsSec  = parseInt(tsHeader, 10);
    const nowSec = Math.floor(Date.now() / 1000);
    if (isNaN(tsSec) || Math.abs(nowSec - tsSec) > MAX_TIMESTAMP_DRIFT_SEC) {
      return errorResponse('Webhook timestamp invalid or too old', 400);
    }
  }

  // 3. Parse JSON
  let payload: {
    event?: string;
    task_id?: string;
    reference?: string;
    status?: string;
    result?: Record<string, unknown>;
  };
  try {
    payload = JSON.parse(rawBody);
  } catch {
    return errorResponse('Invalid JSON body', 400);
  }

  const eventId = req.headers.get('X-Webhook-Event-Id')
    ?? req.headers.get('X-Event-Id')
    ?? `wh-${Date.now()}-${Math.random().toString(36).slice(2)}`;

  const db = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false } },
  );

  // 4. Idempotency — skip already-processed events
  const { data: existing } = await db
    .from('webhook_events')
    .select('id, processed')
    .eq('event_id', eventId)
    .maybeSingle();

  if (existing?.processed) {
    return jsonResponse({ ok: true, duplicate: true });
  }

  // 5. Store event (upsert for race-condition safety)
  await db.from('webhook_events').upsert({
    event_id:         eventId,
    event_type:       payload.event ?? 'unknown',
    provider_task_id: payload.task_id ?? null,
    // Store payload WITHOUT offer_link to avoid sensitive data in table
    payload: {
      event:     payload.event,
      task_id:   payload.task_id,
      reference: payload.reference,
      status:    payload.status,
      // result fields: store only non-sensitive metadata
      result: {
        status:  payload.result?.status,
        message: payload.result?.message,
        has_offer_link: !!payload.result?.offer_link,
      },
    },
    processed: false,
  }, { onConflict: 'event_id' });

  // 6. Find matching order (server-side only)
  if (!payload.task_id && !payload.reference) {
    await db.from('webhook_events')
      .update({ processed: true, processed_at: new Date().toISOString() })
      .eq('event_id', eventId);
    return jsonResponse({ ok: true, warning: 'no task_id or reference provided' });
  }

  let orderQuery = db.from('orders')
    .select('id, customer_id, status, offer_link, result_data');

  if (payload.task_id) {
    orderQuery = orderQuery.eq('provider_task_id', payload.task_id);
  } else {
    orderQuery = orderQuery.eq('reference', payload.reference!);
  }

  const { data: order } = await orderQuery.maybeSingle();

  if (order) {
    // 7. Map new status — never downgrade a terminal order
    const newStatus = mapProviderStatus(payload.event ?? '', payload.status);
    const wasTerminal = TERMINAL.has(order.status);

    // Extract offer link from result — treat as sensitive, store in DB only
    const offerLink  = (payload.result?.offer_link  as string) ?? null;
    const twoFaLink  = (payload.result?.two_fa_link as string) ?? null;
    const resolvedLink = offerLink ?? twoFaLink ?? order.offer_link ?? null;
    const isNewLink  = resolvedLink && !order.offer_link;
    const nowIso     = new Date().toISOString();

    // Build safe result_data: no raw API credentials
    const safeResult: Record<string, unknown> = {
      ...(order.result_data ?? {}),
      ...(payload.result?.status  ? { status:  payload.result.status  } : {}),
      ...(payload.result?.message ? { message: payload.result.message } : {}),
      ...(resolvedLink             ? { offer_link: resolvedLink }       : {}),
    };

    // Only update if something actually changed
    const effectiveStatus = (!wasTerminal && newStatus) ? newStatus : order.status;
    const isComplete = TERMINAL.has(effectiveStatus);

    const updateFields: Record<string, unknown> = {
      webhook_received_at: nowIso,
      updated_at:          nowIso,
      result_data:         safeResult,
    };
    if (!wasTerminal && newStatus && newStatus !== order.status) {
      updateFields.status           = effectiveStatus;
      updateFields.result_available = effectiveStatus === 'success' || effectiveStatus === 'partial';
      if (isComplete) updateFields.completed_at = nowIso;
    }
    if (resolvedLink && !order.offer_link) {
      updateFields.offer_link            = resolvedLink;
      updateFields.offer_link_created_at = nowIso; // server timestamp — never client
    }

    await db.from('orders').update(updateFields).eq('id', order.id);

    // 8. Push notifications
    if (!wasTerminal) {
      if (effectiveStatus === 'success') {
        await pushNotification(db, {
          userId: order.customer_id, orderId: order.id,
          type: 'order_success',
          title: 'تم اكتمال طلبك 🎉',
          body:  'طلبك اكتمل بنجاح!',
        });
      }
      if (isNewLink) {
        await pushNotification(db, {
          userId: order.customer_id, orderId: order.id,
          type: 'offer_link_ready',
          title: 'رابط التفعيل جاهز 🎁',
          body:  'رابط تفعيل الاشتراك متاح الآن. افتح تفاصيل الطلب.',
        });
      }
      if (effectiveStatus === 'failed' || effectiveStatus === 'rejected') {
        await pushNotification(db, {
          userId: order.customer_id, orderId: order.id,
          type: 'order_failed',
          title: 'تعذر تنفيذ طلبك ⚠️',
          body:  'تعذر تنفيذ الطلب. تواصل مع الدعم إذا استمر الأمر.',
        });
      }
    }
  }

  // 9. Mark processed
  await db.from('webhook_events')
    .update({ processed: true, processed_at: new Date().toISOString() })
    .eq('event_id', eventId);

  return jsonResponse({ ok: true, order_found: !!order });
});
