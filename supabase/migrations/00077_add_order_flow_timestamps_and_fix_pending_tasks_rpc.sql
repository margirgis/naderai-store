
-- =============================================================================
-- Migration 00077: Phase-1 Order Flow — timestamps + RPC fix
-- أضيف: queued_at / dispatched_at / received_at لـ pending_tasks
-- وحّد: get_device_pending_tasks يُرجع الحقول الجديدة
-- =============================================================================

-- 1. أضف الأعمدة الجديدة لـ pending_tasks
ALTER TABLE pending_tasks
  ADD COLUMN IF NOT EXISTS queued_at      TIMESTAMPTZ DEFAULT now(),
  ADD COLUMN IF NOT EXISTS dispatched_at  TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS received_at    TIMESTAMPTZ;

-- 2. اضبط queued_at من created_at للسجلات القديمة
UPDATE pending_tasks
SET queued_at = created_at
WHERE queued_at IS NULL AND created_at IS NOT NULL;

-- 3. إعادة بناء get_device_pending_tasks لإرجاع الحقول الجديدة
DROP FUNCTION IF EXISTS public.get_device_pending_tasks(text);

CREATE OR REPLACE FUNCTION public.get_device_pending_tasks(p_device_id TEXT)
RETURNS TABLE (
  task_id                UUID,
  request_id             UUID,
  payment_order_id       UUID,
  amount_requested       NUMERIC,
  sender_phone_requested TEXT,
  sender_name_requested  TEXT,
  fingerprint_amount     NUMERIC,
  credits_amount         NUMERIC,
  order_number           BIGINT,
  credits_requested      INT,
  customer_email         TEXT,
  customer_phone         TEXT,
  customer_name          TEXT,
  payment_method         TEXT,
  request_created_at     TIMESTAMPTZ,
  order_expires_at       TIMESTAMPTZ,
  queued_at              TIMESTAMPTZ,
  dispatched_at          TIMESTAMPTZ,
  received_at            TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  -- تسجيل dispatched_at عند أول استرجاع (إذا لم يُسجَّل بعد)
  UPDATE pending_tasks
  SET dispatched_at = now()
  WHERE device_id   = p_device_id
    AND task_status = 'assigned'
    AND dispatched_at IS NULL;

  RETURN QUERY
  SELECT
    pt.id                                                       AS task_id,
    pt.request_id                                               AS request_id,
    pt.payment_order_id                                         AS payment_order_id,
    COALESCE(po.expected_amount, wtr.amount_requested)          AS amount_requested,
    COALESCE(po.sender_phone,    wtr.sender_phone)              AS sender_phone_requested,
    COALESCE(po.sender_name,     wtr.sender_name)               AS sender_name_requested,
    po.expected_amount                                          AS fingerprint_amount,
    po.credits_qty::NUMERIC                                     AS credits_amount,
    COALESCE(po.order_number,    wtr.order_number)              AS order_number,
    COALESCE(po.credits_qty,     wtr.credits_requested)         AS credits_requested,
    COALESCE(p.email::TEXT,      wtr.customer_email)            AS customer_email,
    COALESCE(p.phone_number,     wtr.customer_phone)            AS customer_phone,
    p.full_name                                                 AS customer_name,
    COALESCE(po.payment_method,  wtr.payment_method)            AS payment_method,
    wtr.created_at                                              AS request_created_at,
    po.expires_at                                               AS order_expires_at,
    COALESCE(pt.queued_at,    pt.created_at)                    AS queued_at,
    pt.dispatched_at                                            AS dispatched_at,
    pt.received_at                                              AS received_at
  FROM pending_tasks pt
  JOIN wallet_topup_requests wtr ON wtr.id = pt.request_id
  LEFT JOIN payment_orders po    ON po.id  = pt.payment_order_id
  LEFT JOIN profiles p           ON p.id   = wtr.customer_id
  WHERE
    pt.device_id    = p_device_id
    AND pt.task_status = 'assigned'
    AND (po.expires_at IS NULL OR po.expires_at > now() - INTERVAL '30 minutes')
    AND COALESCE(po.status,'scanning') NOT IN ('confirmed','cancelled','duplicate','expired')
  ORDER BY pt.created_at DESC;
END;
$$;

COMMENT ON FUNCTION public.get_device_pending_tasks IS
  'v3 (Phase-1): يُرجع مهام الجهاز مع queued_at/dispatched_at/received_at، يُسجّل dispatched_at تلقائياً';

-- 4. RPC لتسجيل received_at عند استلام الجهاز للمهمة (يُستدعى من Android)
CREATE OR REPLACE FUNCTION public.mark_task_received(p_task_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  UPDATE pending_tasks
  SET received_at = COALESCE(received_at, now()),
      updated_at  = now()
  WHERE id = p_task_id AND received_at IS NULL;
END;
$$;
