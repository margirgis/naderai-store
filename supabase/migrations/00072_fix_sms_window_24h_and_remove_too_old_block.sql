
-- ═══════════════════════════════════════════════════════════════════════════
-- Migration 00072: SMS_WINDOW → 24h, remove sms_too_old manual_review block
-- القاعدة الصحيحة: أي SMS عمره أقل من 24 ساعة يُقبل بغض النظر عن وقت إنشاء الطلب
-- ═══════════════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION confirm_payment_order(
  p_order_id        UUID,
  p_transaction_id  TEXT,
  p_received_amount NUMERIC,
  p_sender_phone    TEXT    DEFAULT NULL,
  p_sender_name     TEXT    DEFAULT NULL,
  p_sms_timestamp   TIMESTAMPTZ DEFAULT NULL,
  p_device_id       TEXT    DEFAULT NULL,
  p_sms_body        TEXT    DEFAULT NULL,
  p_idempotency_key TEXT    DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_order          payment_orders%ROWTYPE;
  v_topup          wallet_topup_requests%ROWTYPE;
  v_balance_before NUMERIC;
  v_received_phone TEXT;
  v_expected_phone TEXT;
  -- أي SMS عمره أقل من 24 ساعة مقبول — بغض النظر عن وقت إنشاء الطلب
  MAX_SMS_AGE      CONSTANT INTERVAL := INTERVAL '24 hours';
BEGIN

  RAISE NOTICE '[confirm_payment_order] order=% tx=% device=% amount=%',
    p_order_id, p_transaction_id, p_device_id, p_received_amount;

  -- ── STEP 1: Idempotency + duplicate checks ─────────────────────────────
  IF p_transaction_id IS NOT NULL
     AND p_transaction_id NOT LIKE 'DEVICE-%'
     AND p_transaction_id NOT LIKE 'SMS-%'
     AND p_transaction_id NOT LIKE 'AUTO-%'
  THEN
    -- نفس الطلب سبق تأكيده بنفس الـ transaction_id
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions ct
      JOIN wallet_topup_requests wtr ON ct.order_id = wtr.id
      WHERE ct.transaction_id = p_transaction_id AND wtr.payment_order_id = p_order_id
    ) THEN
      RETURN jsonb_build_object('ok',true,'idempotent',true,'scan_status','confirmed',
        'reason','already_confirmed','order_id',p_order_id);
    END IF;

    -- نفس الـ transaction_id في طلب آخر مختلف → duplicate
    IF EXISTS (
      SELECT 1 FROM confirmed_transactions ct
      WHERE ct.transaction_id = p_transaction_id
        AND ct.order_id NOT IN (
          SELECT id FROM wallet_topup_requests WHERE payment_order_id = p_order_id
        )
    ) THEN
      INSERT INTO security_audit_log(event_type,order_id,device_id,details)
      VALUES ('replay_transaction_id_cross_order',p_order_id,p_device_id,
        jsonb_build_object('transaction_id',p_transaction_id,'amount',p_received_amount));
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية مستخدم في طلب آخر: '||p_transaction_id,
        verification_status='no_match' WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate',
        'reason','duplicate_transaction_id_cross_order');
    END IF;

    -- triple-match duplicate
    IF is_triple_duplicate(p_transaction_id, p_sender_phone, p_received_amount, NULL) THEN
      INSERT INTO security_audit_log(event_type,order_id,device_id,details)
      VALUES ('triple_match_duplicate',p_order_id,p_device_id,
        jsonb_build_object('transaction_id',p_transaction_id,
          'sender_phone',p_sender_phone,'amount',p_received_amount));
      UPDATE payment_orders SET status='duplicate',
        failure_reason='عملية مكررة: triple-match',
        verification_status='no_match' WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate','reason','triple_match_duplicate');
    END IF;

    -- ledger duplicate
    IF EXISTS (
      SELECT 1 FROM payment_transactions
      WHERE transaction_id=p_transaction_id AND status='accepted' AND order_id<>p_order_id
    ) THEN
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية مستخدم (ledger): '||p_transaction_id WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate',
        'reason','duplicate_transaction_id_ledger');
    END IF;
  END IF;

  -- ── STEP 2: Lock payment_order ─────────────────────────────────────────
  SELECT * INTO v_order FROM payment_orders WHERE id=p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','order_not_found');
  END IF;

  SELECT * INTO v_topup FROM wallet_topup_requests WHERE payment_order_id=p_order_id LIMIT 1;
  IF v_topup.id IS NULL THEN
    SELECT * INTO v_topup FROM wallet_topup_requests
    WHERE notes LIKE '%payment_order_id:'||p_order_id::text||'%' LIMIT 1;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET payment_order_id=p_order_id WHERE id=v_topup.id;
    END IF;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM profiles WHERE id=v_order.user_id) THEN
    RETURN jsonb_build_object('ok',false,'reason','user_not_found');
  END IF;

  -- ── STEP 3: Expiry ─────────────────────────────────────────────────────
  IF v_order.expires_at <= now() AND v_order.status NOT IN ('reopened') THEN
    UPDATE payment_orders SET status='expired' WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET status='expired' WHERE id=v_topup.id;
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','failed','reason','order_expired');
  END IF;

  -- ── STEP 4: Terminal status ────────────────────────────────────────────
  IF v_order.status='confirmed' THEN
    RETURN jsonb_build_object('ok',true,'idempotent',true,'scan_status','confirmed','reason','already_confirmed');
  END IF;
  IF v_order.status IN ('cancelled','failed','expired','duplicate') THEN
    RETURN jsonb_build_object('ok',false,'scan_status',v_order.status,'reason','order_in_terminal_status');
  END IF;

  -- ── STEP 5: SMS age check — الرسالة يجب ألا يتجاوز عمرها 24 ساعة ──────
  -- لا نرفض رسالة بسبب وقت إنشاء الطلب — المهم أن الرسالة ليست قديمة جداً (> 24h)
  IF p_sms_timestamp IS NOT NULL THEN
    IF p_sms_timestamp < (now() - MAX_SMS_AGE) THEN
      RAISE NOTICE '[SMS_EXPIRED] sms=% max_age=24h now=%', p_sms_timestamp, now();
      UPDATE payment_orders SET
        status='manual_review',
        failure_reason='SMS عمره أكثر من 24 ساعة — يحتاج مراجعة يدوية'
      WHERE id=p_order_id
        AND status NOT IN ('confirmed','cancelled','failed','expired','duplicate');
      RETURN jsonb_build_object('ok',false,'scan_status','manual_review','reason','sms_expired_24h',
        'sms_timestamp',p_sms_timestamp,'now',now());
    END IF;
  END IF;

  -- ── STEP 6: Sender phone (تخطى لو أحد الطرفين فاضي) ──────────────────
  v_expected_phone := normalize_egyptian_phone(COALESCE(v_order.sender_phone,''));
  v_received_phone := normalize_egyptian_phone(COALESCE(p_sender_phone,''));

  IF v_expected_phone <> '' AND v_received_phone <> ''
     AND v_received_phone <> v_expected_phone
  THEN
    RAISE NOTICE '[SENDER_PHONE_MISMATCH] expected=% got=%', v_expected_phone, v_received_phone;
    INSERT INTO security_audit_log(event_type,user_id,order_id,device_id,details)
    VALUES ('sender_phone_mismatch',v_order.user_id,p_order_id,p_device_id,
      jsonb_build_object('expected',v_order.sender_phone,'received',p_sender_phone));
    UPDATE payment_orders SET status='manual_review',verification_status='no_match',
      failure_reason='رقم المحول غير مطابق' WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET scan_status='manual_review',
        failure_reason='رقم المحول غير مطابق' WHERE id=v_topup.id;
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','manual_review',
      'reason','sender_phone_mismatch',
      'expected',v_order.sender_phone,'received',p_sender_phone);
  END IF;

  -- ── STEP 7: Amount match ───────────────────────────────────────────────
  IF ROUND(p_received_amount::numeric,2) <> ROUND(v_order.expected_amount::numeric,2) THEN
    RAISE NOTICE '[AMOUNT_MISMATCH] expected=% got=%', v_order.expected_amount, p_received_amount;
    INSERT INTO security_audit_log(event_type,user_id,order_id,device_id,details)
    VALUES ('amount_mismatch',v_order.user_id,p_order_id,p_device_id,
      jsonb_build_object('expected',v_order.expected_amount,'received',p_received_amount));
    UPDATE payment_orders SET status='amount_mismatch',
      failure_reason='مبلغ غير مطابق: مطلوب '||v_order.expected_amount||' تم استلام '||p_received_amount
    WHERE id=p_order_id;
    IF v_topup.id IS NOT NULL THEN
      UPDATE wallet_topup_requests SET scan_status='manual_review',
        failure_reason='المبلغ غير مطابق: مطلوب '||v_order.expected_amount||' تم استلام '||p_received_amount
      WHERE id=v_topup.id AND status NOT IN ('approved','rejected');
    END IF;
    RETURN jsonb_build_object('ok',false,'scan_status','manual_review',
      'reason','amount_mismatch',
      'expected',v_order.expected_amount,'received',p_received_amount);
  END IF;

  -- ── STEP 8: Atomic reserve ─────────────────────────────────────────────
  IF p_transaction_id IS NOT NULL AND p_transaction_id <> '' THEN
    BEGIN
      INSERT INTO payment_transactions(transaction_id,order_id,sender_phone,sender_name,
        amount,status,device_id,sms_body,confirmed_at)
      VALUES (p_transaction_id,p_order_id,p_sender_phone,p_sender_name,
        p_received_amount,'accepted',p_device_id,p_sms_body,now());
    EXCEPTION WHEN unique_violation THEN
      UPDATE payment_orders SET status='duplicate',
        failure_reason='رقم العملية وُجد مسبقاً (race): '||p_transaction_id WHERE id=p_order_id;
      RETURN jsonb_build_object('ok',false,'scan_status','duplicate','reason','race_condition_duplicate');
    END;
    BEGIN
      INSERT INTO sms_transaction_receipts(transaction_id,sender_phone,sender_name,
        amount,sms_body,device_id,payment_order_id,status)
      VALUES (p_transaction_id,p_sender_phone,p_sender_name,p_received_amount,
        p_sms_body,p_device_id,p_order_id,'accepted');
    EXCEPTION WHEN unique_violation OR not_null_violation THEN NULL;
    END;
  END IF;

  -- ── STEP 9: Confirm + credit wallet ───────────────────────────────────
  RAISE NOTICE '[PAYMENT_CONFIRMED] order=% tx=% credits=% user=%',
    p_order_id, p_transaction_id, v_order.credits_qty, v_order.user_id;

  UPDATE payment_orders SET
    status='confirmed', verified_at=now(), confirmed_at=now(),
    transaction_id=p_transaction_id, verification_status='completed', failure_reason=NULL
  WHERE id=p_order_id;

  SELECT COALESCE(wallet_balance,0) INTO v_balance_before FROM profiles WHERE id=v_order.user_id;
  UPDATE profiles
    SET wallet_balance=COALESCE(wallet_balance,0)+COALESCE(v_order.credits_qty,0)
  WHERE id=v_order.user_id;

  INSERT INTO wallet_transactions(customer_id,type,amount,balance_before,balance_after,reason,reference)
  VALUES (v_order.user_id,'credit',v_order.credits_qty,v_balance_before,
    v_balance_before+v_order.credits_qty,
    'شحن رصيد تلقائي - طلب #'||v_order.order_number,
    'PAY-ORDER-'||p_order_id::text);

  -- ── STEP 10: Update topup_request + confirmed_transactions ────────────
  IF v_topup.id IS NOT NULL THEN
    UPDATE wallet_topup_requests SET
      status='approved', scan_status='approved', verification_status='completed',
      transaction_id=p_transaction_id, payment_order_id=p_order_id,
      sender_phone=COALESCE(p_sender_phone,sender_phone),
      sender_name=COALESCE(p_sender_name,sender_name),
      matched_automatically=true, confirmed_at=now(), processed_at=now(), failure_reason=NULL
    WHERE id=v_topup.id AND status NOT IN ('approved','rejected');

    BEGIN
      INSERT INTO confirmed_transactions(transaction_id,order_id,user_id,
        sender_phone,sender_name,amount,status,confirmed_at)
      VALUES (COALESCE(p_transaction_id,'AUTO-'||p_order_id::text),
        v_topup.id, v_order.user_id,
        p_sender_phone, p_sender_name, p_received_amount, 'confirmed', now());
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
  END IF;

  -- ── Audit logs ────────────────────────────────────────────────────────
  BEGIN
    INSERT INTO admin_audit_log(admin_id,action,target_user,target_ref,amount,
      balance_before,balance_after,reason,metadata)
    VALUES (NULL,'auto_confirm_payment_order',v_order.user_id,p_order_id::text,
      p_received_amount,v_balance_before,v_balance_before+v_order.credits_qty,
      'تأكيد تلقائي - طلب #'||v_order.order_number,
      jsonb_build_object('transaction_id',p_transaction_id,'credits_added',v_order.credits_qty,
        'device_id',p_device_id,'topup_id',v_topup.id));
  EXCEPTION WHEN others THEN NULL;
  END;

  BEGIN
    INSERT INTO financial_audit_log(event_type,order_id,transaction_id,actor,amount,metadata)
    VALUES ('payment_confirmed',p_order_id,p_transaction_id,
      COALESCE('device:'||p_device_id,'system'),p_received_amount,
      jsonb_build_object('credits_added',v_order.credits_qty,'user_id',v_order.user_id,
        'topup_id',v_topup.id));
  EXCEPTION WHEN others THEN NULL;
  END;

  INSERT INTO notifications(user_id,type,title,body,order_id)
  VALUES (v_order.user_id,'wallet_topup','تم تأكيد طلب الشحن ✅',
    'تم إضافة '||v_order.credits_qty||' Credit إلى محفظتك — طلب #'||v_order.order_number,NULL);

  PERFORM create_admin_notification(
    p_title        := '✅ تم تأكيد دفعة',
    p_message      := 'طلب #'||v_order.order_number||' — '||p_received_amount||' جنيه'
                      ||COALESCE(' — '||p_sender_phone,'')||COALESCE(' tx:'||p_transaction_id,''),
    p_event_type   := 'payment_confirmed',
    p_reference_id := p_order_id::text,
    p_device_id    := p_device_id
  );

  RETURN jsonb_build_object(
    'ok',true,'scan_status','confirmed',
    'order_id',p_order_id,'order_number',v_order.order_number,
    'credits_added',v_order.credits_qty,'transaction_id',p_transaction_id,
    'balance_before',v_balance_before,
    'balance_after',v_balance_before+v_order.credits_qty
  );
END;
$$;
