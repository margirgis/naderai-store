CREATE OR REPLACE FUNCTION get_all_orders_for_admin()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_orders JSONB;
BEGIN
  SELECT COALESCE(jsonb_agg(o), '[]'::jsonb) INTO v_orders
  FROM (
    SELECT
      wtr.id                         AS request_id,
      wtr.order_number,
      po.id                          AS payment_order_id,
      po.order_number                AS payment_order_number,
      wtr.status,
      wtr.scan_status,
      COALESCE(wtr.fingerprint_amount, wtr.amount) AS amount_requested,
      wtr.credits_requested,
      wtr.sender_phone,
      wtr.sender_name,
      wtr.payment_method,
      wtr.assigned_device_id,
      wtr.created_at,
      wtr.updated_at,
      wtr.confirmed_at,
      wtr.failure_reason,
      wtr.transaction_id,
      p.email                        AS customer_email,
      p.phone                        AS customer_phone,
      po.expires_at                  AS order_expires_at,
      pt.id                          AS task_id,
      pt.task_status
    FROM wallet_topup_requests wtr
    LEFT JOIN payment_orders po ON po.id = wtr.payment_order_id
    LEFT JOIN profiles p ON p.id = wtr.customer_id
    LEFT JOIN LATERAL (
      SELECT id, task_status FROM pending_tasks
      WHERE request_id = wtr.id
        AND task_status IN ('pending', 'assigned', 'in_progress')
      ORDER BY created_at DESC
      LIMIT 1
    ) pt ON true
    ORDER BY wtr.created_at DESC
    LIMIT 500
  ) o;

  RETURN jsonb_build_object('ok', true, 'orders', v_orders);
END;
$$;