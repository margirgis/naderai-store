DROP FUNCTION IF EXISTS auto_confirm_wallet_topup(text, numeric);

CREATE OR REPLACE FUNCTION auto_confirm_wallet_topup(p_sender_phone TEXT, p_amount NUMERIC)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  matched wallet_topup_requests%ROWTYPE;
  profile profiles%ROWTYPE;
  new_balance NUMERIC;
  norm_phone TEXT;
BEGIN
  norm_phone := regexp_replace(p_sender_phone, '[^0-9]', '', 'g');

  SELECT *
  INTO matched
  FROM wallet_topup_requests
  WHERE status = 'pending'
    AND sender_phone IS NOT NULL
    AND regexp_replace(sender_phone, '[^0-9]', '', 'g') = norm_phone
    AND ABS(wallet_topup_requests.amount - p_amount) < 0.5
  ORDER BY created_at DESC
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'No matching pending request', 'sender_phone', norm_phone, 'amount', p_amount);
  END IF;

  SELECT * INTO profile FROM profiles WHERE id = matched.customer_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Customer profile not found');
  END IF;

  new_balance := COALESCE(profile.wallet_balance, 0) + matched.amount;

  UPDATE profiles SET wallet_balance = new_balance WHERE id = matched.customer_id;

  INSERT INTO wallet_transactions (customer_id, type, amount, balance_after, reason, reference)
  VALUES (matched.customer_id, 'credit', matched.amount, new_balance, 'Vodafone Cash auto-confirmation', COALESCE(matched.transaction_reference, 'SMS-' || EXTRACT(EPOCH FROM NOW())::TEXT));

  UPDATE wallet_topup_requests
  SET status = 'approved', processed_at = NOW(), notes = 'Auto-approved from SMS sender ' || p_sender_phone
  WHERE id = matched.id;

  INSERT INTO notifications (user_id, type, title, body)
  VALUES (matched.customer_id, 'wallet_topup', 'تم شحن رصيدك تلقائياً', 'تمت إضافة ' || matched.amount || ' Credit إلى محفظتك. رصيدك الآن: ' || new_balance || ' Credit.');

  RETURN jsonb_build_object('ok', true, 'request_id', matched.id, 'new_balance', new_balance);
END;
$$;
