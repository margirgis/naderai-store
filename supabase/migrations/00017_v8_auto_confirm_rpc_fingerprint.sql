DROP FUNCTION IF EXISTS auto_confirm_wallet_topup(text, numeric);

CREATE OR REPLACE FUNCTION auto_confirm_wallet_topup(
  p_sender_phone TEXT,
  p_amount NUMERIC,
  p_sender_name TEXT DEFAULT NULL,
  p_transaction_id TEXT DEFAULT NULL,
  p_sms_body TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  matched wallet_topup_requests%ROWTYPE;
  profile profiles%ROWTYPE;
  new_balance NUMERIC;
  norm_phone TEXT;
  sms_log_id UUID;
BEGIN
  norm_phone := normalize_egyptian_phone(p_sender_phone);

  -- Log the incoming SMS first
  INSERT INTO sms_logs (sender_phone, sender_name, amount, transaction_id, sms_body)
  VALUES (norm_phone, p_sender_name, p_amount, p_transaction_id, p_sms_body)
  RETURNING id INTO sms_log_id;

  -- Match by phone AND fingerprint_amount (within 0.01)
  SELECT *
  INTO matched
  FROM wallet_topup_requests
  WHERE status = 'pending'
    AND sender_phone IS NOT NULL
    AND normalize_egyptian_phone(sender_phone) = norm_phone
    AND fingerprint_amount IS NOT NULL
    AND ABS(fingerprint_amount - p_amount) <= 0.01
  ORDER BY created_at ASC
  LIMIT 1;

  IF NOT FOUND THEN
    -- Try fallback: phone + total amount (no fingerprint, backwards compat)
    SELECT *
    INTO matched
    FROM wallet_topup_requests
    WHERE status = 'pending'
      AND sender_phone IS NOT NULL
      AND normalize_egyptian_phone(sender_phone) = norm_phone
      AND fingerprint_amount IS NULL
      AND ABS(amount - p_amount) <= 0.5
    ORDER BY created_at ASC
    LIMIT 1;
  END IF;

  IF NOT FOUND THEN
    RETURN jsonb_build_object(
      'ok', false,
      'reason', 'No matching pending request',
      'sender_phone', norm_phone,
      'amount', p_amount,
      'sms_log_id', sms_log_id
    );
  END IF;

  -- Update sms_log with matched request
  UPDATE sms_logs SET matched_request_id = matched.id WHERE id = sms_log_id;

  SELECT * INTO profile FROM profiles WHERE id = matched.customer_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Customer profile not found');
  END IF;

  new_balance := COALESCE(profile.wallet_balance, 0) + matched.amount;

  UPDATE profiles SET wallet_balance = new_balance WHERE id = matched.customer_id;

  INSERT INTO wallet_transactions (customer_id, type, amount, balance_after, reason, reference)
  VALUES (
    matched.customer_id, 'credit', matched.amount, new_balance,
    'Vodafone Cash auto-confirmation',
    COALESCE(p_transaction_id, matched.transaction_reference, 'SMS-' || EXTRACT(EPOCH FROM NOW())::TEXT)
  );

  UPDATE wallet_topup_requests
  SET
    status = 'approved',
    processed_at = NOW(),
    matched_automatically = TRUE,
    sender_name = COALESCE(p_sender_name, sender_name),
    transaction_id = COALESCE(p_transaction_id, transaction_id),
    notes = 'Auto-approved via SMS. Sender: ' || norm_phone
      || CASE WHEN p_sender_name IS NOT NULL THEN ' (' || p_sender_name || ')' ELSE '' END
      || CASE WHEN p_transaction_id IS NOT NULL THEN '. TxID: ' || p_transaction_id ELSE '' END
  WHERE id = matched.id;

  INSERT INTO notifications (user_id, type, title, body)
  VALUES (
    matched.customer_id,
    'wallet_topup',
    'تم شحن رصيدك تلقائياً',
    'تمت إضافة ' || matched.amount || ' Credit إلى محفظتك. رصيدك الآن: ' || new_balance || ' Credit.'
  );

  RETURN jsonb_build_object(
    'ok', true,
    'request_id', matched.id,
    'new_balance', new_balance,
    'sms_log_id', sms_log_id
  );
END;
$$;
