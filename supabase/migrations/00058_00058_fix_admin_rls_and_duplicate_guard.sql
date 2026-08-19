
-- ══════════════════════════════════════════════════════════════════════════
-- MIGRATION 00058: Fix 3 critical issues
-- 1. Add admin_all SELECT policy to wallet_topup_requests (Realtime + REST)
-- 2. Fix atomic_confirm_topup race condition (INSERT before wallet update)
-- ══════════════════════════════════════════════════════════════════════════

-- ── FIX 1: wallet_topup_requests — explicit admin_all policy ─────────────
DROP POLICY IF EXISTS "admin_all_wallet_topup_requests" ON public.wallet_topup_requests;
CREATE POLICY "admin_all_wallet_topup_requests"
  ON public.wallet_topup_requests
  FOR ALL
  USING (
    EXISTS (
      SELECT 1 FROM public.profiles
      WHERE profiles.id = auth.uid()
        AND profiles.role = 'admin'
    )
  );

ALTER TABLE public.wallet_topup_requests ENABLE ROW LEVEL SECURITY;

-- ── FIX 2: atomic_confirm_topup — INSERT confirmed_transactions FIRST ────
-- ensures no double-credit: wallet is only touched AFTER tx_id is locked
CREATE OR REPLACE FUNCTION public.atomic_confirm_topup(
  p_order_id        UUID,
  p_transaction_id  TEXT,
  p_sender_phone    TEXT        DEFAULT NULL,
  p_sender_name     TEXT        DEFAULT NULL,
  p_amount          NUMERIC     DEFAULT NULL,
  p_receiver_wallet TEXT        DEFAULT NULL,
  p_device_id       TEXT        DEFAULT NULL,
  p_transaction_time TIMESTAMPTZ DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $$
DECLARE
  req             RECORD;
  credits_to_add  NUMERIC;
  current_balance NUMERIC;
  new_balance     NUMERIC;
  norm_received   TEXT;
  norm_expected   TEXT;
  v_expected      NUMERIC;
  v_received      NUMERIC;
BEGIN
  -- Lock topup row first (serializes parallel calls on same order)
  SELECT * INTO req FROM wallet_topup_requests WHERE id = p_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'order_not_found');
  END IF;

  -- Idempotent: already approved
  IF req.status = 'approved' THEN
    RETURN jsonb_build_object('ok', true, 'idempotent', true,
      'reason', 'already_confirmed', 'order_id', p_order_id);
  END IF;

  IF req.status NOT IN ('pending', 'scanning') THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'invalid_order_status',
      'current_status', req.status);
  END IF;

  -- ── Sender phone validation ──────────────────────────────────────────────
  norm_received := normalize_egyptian_phone(COALESCE(p_sender_phone, ''));
  norm_expected := normalize_egyptian_phone(COALESCE(req.sender_phone, ''));
  IF norm_expected <> '' AND norm_received <> '' AND norm_received <> norm_expected THEN
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('sender_phone_mismatch_topup', p_order_id, p_device_id,
      jsonb_build_object('expected', req.sender_phone, 'received', p_sender_phone));
    UPDATE wallet_topup_requests
    SET scan_status = 'manual_review',
        failure_reason = 'رقم المحول غير مطابق: متوقع ' || req.sender_phone || ' تم استلام ' || p_sender_phone,
        updated_at = now()
    WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');
    RETURN jsonb_build_object('ok', false, 'reason', 'sender_phone_mismatch',
      'expected', req.sender_phone, 'received', p_sender_phone);
  END IF;

  -- ── Amount validation ────────────────────────────────────────────────────
  v_expected := ROUND(COALESCE(req.fingerprint_amount, req.amount)::numeric, 2);
  v_received  := ROUND(p_amount::numeric, 2);
  IF v_received <> v_expected THEN
    INSERT INTO security_audit_log(event_type, order_id, device_id, details)
    VALUES ('amount_mismatch_topup', p_order_id, p_device_id,
      jsonb_build_object('expected', v_expected, 'received', v_received));
    UPDATE wallet_topup_requests
    SET scan_status    = 'amount_mismatch',
        failure_reason = 'المبلغ غير مطابق: مطلوب ' || v_expected || ' تم استلام ' || v_received,
        updated_at     = now()
    WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');
    RETURN jsonb_build_object('ok', false, 'reason', 'amount_mismatch',
      'expected', v_expected, 'received', v_received);
  END IF;

  -- ── STEP 1: Lock transaction_id in confirmed_transactions BEFORE wallet ──
  -- This is the ONLY authoritative guard — UNIQUE constraint enforces atomicity
  IF p_transaction_id IS NOT NULL AND p_transaction_id <> '' THEN
    BEGIN
      INSERT INTO confirmed_transactions(
        transaction_id, order_id, user_id,
        sender_phone, sender_name, amount, status, confirmed_at
      ) VALUES (
        p_transaction_id, p_order_id, req.customer_id,
        p_sender_phone, p_sender_name, p_amount, 'confirmed', now()
      );
    EXCEPTION WHEN unique_violation THEN
      RAISE NOTICE '[DUPLICATE_TX] tx=% already confirmed — rejecting order=%',
        p_transaction_id, p_order_id;
      UPDATE wallet_topup_requests
      SET status         = 'rejected',
          scan_status    = 'rejected',
          failure_reason = 'رقم العملية مستخدم مسبقاً: ' || p_transaction_id,
          updated_at     = now()
      WHERE id = p_order_id AND status NOT IN ('approved', 'rejected');
      INSERT INTO security_audit_log(event_type, order_id, device_id, details)
      VALUES ('duplicate_transaction_id', p_order_id, p_device_id,
        jsonb_build_object('transaction_id', p_transaction_id, 'amount', p_amount));
      RETURN jsonb_build_object('ok', false, 'reason', 'duplicate_transaction_id',
        'order_id', p_order_id, 'transaction_id', p_transaction_id);
    END;

    -- Secondary audit log (best effort)
    BEGIN
      INSERT INTO sms_transaction_receipts(
        transaction_id, sender_phone, sender_name, amount,
        receiver_wallet, device_id, topup_request_id, status
      ) VALUES (
        p_transaction_id, p_sender_phone, p_sender_name, p_amount,
        p_receiver_wallet, p_device_id, p_order_id, 'accepted'
      );
    EXCEPTION WHEN unique_violation OR not_null_violation THEN NULL;
    END;
  END IF;

  -- ── STEP 2: Credit wallet — only after tx_id exclusively locked ──────────
  credits_to_add := COALESCE(req.credits_requested, req.amount);
  SELECT wallet_balance INTO current_balance FROM profiles WHERE id = req.customer_id;
  new_balance := COALESCE(current_balance, 0) + credits_to_add;

  UPDATE profiles SET wallet_balance = new_balance, updated_at = now()
  WHERE id = req.customer_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Profile not found for user %', req.customer_id;
  END IF;

  -- ── STEP 3: Mark topup approved ──────────────────────────────────────────
  UPDATE wallet_topup_requests SET
    status               = 'approved',
    scan_status          = 'approved',
    processed_at         = now(),
    confirmed_at         = now(),
    matched_automatically = TRUE,
    transaction_id       = p_transaction_id,
    sender_name          = COALESCE(p_sender_name, sender_name),
    assigned_device_id   = COALESCE(p_device_id, assigned_device_id),
    failure_reason       = NULL,
    updated_at           = now()
  WHERE id = p_order_id;

  INSERT INTO wallet_transactions(
    customer_id, type, amount, balance_before, balance_after, reason, reference
  ) VALUES (
    req.customer_id, 'credit', credits_to_add,
    COALESCE(current_balance, 0), new_balance,
    'Vodafone Cash auto-confirmation', p_transaction_id
  );

  INSERT INTO notifications(user_id, type, title, body)
  VALUES (req.customer_id, 'wallet_topup',
    'تم شحن رصيدك تلقائياً ✅',
    'تمت إضافة ' || credits_to_add || ' Credit. رصيدك الآن: ' || new_balance || ' Credit.');

  IF p_device_id IS NOT NULL THEN
    UPDATE sms_device_status
    SET last_order_processed_at = now(), updated_at = now()
    WHERE device_id = p_device_id;
  END IF;

  RAISE NOTICE '[ATOMIC_CONFIRM] SUCCESS order=% tx=% credits=% user=%',
    p_order_id, p_transaction_id, credits_to_add, req.customer_id;

  RETURN jsonb_build_object(
    'ok', true, 'confirmed', true, 'order_id', p_order_id,
    'new_balance', new_balance, 'transaction_id', p_transaction_id,
    'credits_added', credits_to_add, 'scan_status', 'confirmed'
  );
END;
$$;

-- ── VERIFY policies ───────────────────────────────────────────────────────
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT policyname, cmd FROM pg_policies
    WHERE tablename='wallet_topup_requests' AND schemaname='public'
    ORDER BY cmd, policyname
  LOOP
    RAISE NOTICE '[POLICY] % — %', r.cmd, r.policyname;
  END LOOP;
END $$;
