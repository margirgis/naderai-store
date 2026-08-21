
-- =============================================================================
-- Migration 00073
-- =============================================================================

-- 1. حذف النسخة القديمة (10 params, SMS_WINDOW=60min, p_scan_id)
DROP FUNCTION IF EXISTS public.confirm_payment_order(
  uuid, text, numeric, text, text, timestamptz, text, text, text, text
);

-- 2. إصلاح phantom scans في wallet_topup_requests
UPDATE wallet_topup_requests
SET status = 'pending', scan_status = 'pending',
    failure_reason = 'أُعيد تعيينه — phantom scan (scanning > 2h)',
    updated_at = now()
WHERE status = 'scanning'
  AND updated_at < now() - INTERVAL '2 hours';

-- 3. إصلاح payment_orders المقابلة
UPDATE payment_orders
SET status = 'scanning', failure_reason = 'أُعيد تعيينه — phantom scan reset', updated_at = now()
WHERE status = 'scanning'
  AND updated_at < now() - INTERVAL '2 hours'
  AND status NOT IN ('confirmed','cancelled','failed','duplicate','expired');

-- 4. إنهاء pending_tasks لطلبات منتهية
UPDATE pending_tasks pt
SET task_status = 'completed', result_status = 'failure',
    failure_reason = 'payment_order منتهي الصلاحية',
    completed_at = now(), updated_at = now()
FROM payment_orders po
WHERE pt.payment_order_id = po.id
  AND po.status IN ('expired','cancelled')
  AND pt.task_status IN ('assigned','pending');

-- 5. حذف ثم إعادة إنشاء get_device_pending_tasks
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
  order_expires_at       TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
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
    po.expires_at                                               AS order_expires_at
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
  'v2: يُرجع مهام الجهاز — يُستبعد المنتهي والنهائي، SMS_MAX_AGE=24h في confirm_payment_order';
