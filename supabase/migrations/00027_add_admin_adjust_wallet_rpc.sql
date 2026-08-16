
-- ═══════════════════════════════════════════════════════════════════════
-- RPC: admin_adjust_wallet — تعديل رصيد العميل بشكل آمن مع سجل كامل
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION public.admin_adjust_wallet(
  p_admin_id   UUID,
  p_customer_id UUID,
  p_type       TEXT,    -- 'credit' | 'debit'
  p_amount     NUMERIC,
  p_reason     TEXT,
  p_reference  TEXT DEFAULT NULL
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  current_balance NUMERIC;
  new_balance     NUMERIC;
  admin_role      TEXT;
BEGIN
  -- التحقق من صلاحية المسؤول
  SELECT role INTO admin_role FROM profiles WHERE id = p_admin_id;
  IF admin_role IS DISTINCT FROM 'admin' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'unauthorized');
  END IF;

  IF p_type NOT IN ('credit', 'debit') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'invalid_type');
  END IF;
  IF p_amount <= 0 THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'amount_must_be_positive');
  END IF;

  -- قفل صف المستخدم للعمليات الذرية
  SELECT wallet_balance INTO current_balance FROM profiles WHERE id = p_customer_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'customer_not_found');
  END IF;

  IF p_type = 'debit' AND COALESCE(current_balance, 0) < p_amount THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'insufficient_balance',
      'current_balance', current_balance, 'requested', p_amount);
  END IF;

  new_balance := CASE
    WHEN p_type = 'credit' THEN COALESCE(current_balance, 0) + p_amount
    ELSE COALESCE(current_balance, 0) - p_amount
  END;

  UPDATE profiles SET wallet_balance = new_balance, updated_at = now()
  WHERE id = p_customer_id;

  -- سجل المعاملة مع balance_before
  INSERT INTO wallet_transactions(customer_id, type, amount, balance_before, balance_after, reason, reference, created_by)
  VALUES (p_customer_id, p_type, p_amount, COALESCE(current_balance, 0), new_balance,
    p_reason, p_reference, p_admin_id);

  -- سجل التدقيق
  INSERT INTO admin_audit_log(admin_id, action, target_user, amount, balance_before, balance_after, reason, metadata)
  VALUES (p_admin_id, p_type, p_customer_id, p_amount, COALESCE(current_balance, 0), new_balance,
    p_reason, jsonb_build_object('reference', p_reference));

  RETURN jsonb_build_object(
    'ok', true,
    'type', p_type,
    'amount', p_amount,
    'balance_before', COALESCE(current_balance, 0),
    'balance_after', new_balance
  );
END;
$$;

-- ═══════════════════════════════════════════════════════════════════════
-- RPC: admin_rescan_topup_request — إعادة فحص طلب شحن (مسؤول فقط)
-- ═══════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION public.admin_rescan_topup_request(
  p_admin_id   UUID,
  p_request_id UUID,
  p_reason     TEXT DEFAULT 'إعادة فحص يدوي من المسؤول'
) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
  req        RECORD;
  admin_role TEXT;
  task_result JSONB;
BEGIN
  SELECT role INTO admin_role FROM profiles WHERE id = p_admin_id;
  IF admin_role IS DISTINCT FROM 'admin' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'unauthorized');
  END IF;

  SELECT * INTO req FROM wallet_topup_requests WHERE id = p_request_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'request_not_found');
  END IF;

  IF req.status = 'approved' THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'already_approved');
  END IF;

  -- تسجيل في سجل التدقيق
  INSERT INTO admin_audit_log(admin_id, action, target_user, target_ref, reason, metadata)
  VALUES (p_admin_id, 'rescan', req.customer_id, p_request_id::TEXT, p_reason,
    jsonb_build_object('old_scan_status', req.scan_status, 'old_status', req.status));

  -- إعادة الطلب لحالة pending وتسجيل التاريخ
  PERFORM insert_order_status_history(
    p_request_id, req.status, 'pending', 'rescanning',
    p_admin_id, NULL, p_reason, NULL
  );

  UPDATE wallet_topup_requests SET
    status = 'pending', scan_status = 'rescanning',
    failure_reason = NULL, updated_at = now()
  WHERE id = p_request_id;

  -- إنشاء مهمة فحص جديدة
  task_result := create_pending_task_from_wallet_request(p_request_id);

  RETURN jsonb_build_object(
    'ok', true,
    'request_id', p_request_id,
    'task', task_result
  );
END;
$$;
