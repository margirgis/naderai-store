CREATE OR REPLACE FUNCTION auto_confirm_wallet_topup(sender_phone TEXT, amount NUMERIC)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  matched wallet_topup_requests%ROWTYPE;
  profile profiles%ROWTYPE;
  new_balance NUMERIC;
  result JSONB;
BEGIN
  -- Find latest matching pending request
  SELECT *
  INTO matched
  FROM wallet_topup_requests
  WHERE status = 'pending'
    AND sender_phone IS NOT NULL
    AND ABS(amount - amount) < 0.5
  ORDER BY created_at DESC
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'No matching pending request');
  END IF;

  -- Get customer profile
  SELECT * INTO profile FROM profiles WHERE id = matched.customer_id;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('ok', false, 'reason', 'Customer profile not found');
  END IF;

  new_balance := COALESCE(profile.wallet_balance, 0) + matched.amount;

  -- Update wallet
  UPDATE profiles SET wallet_balance = new_balance WHERE id = matched.customer_id;

  -- Insert transaction
  INSERT INTO wallet_transactions (customer_id, type, amount, balance_after, reason, reference)
  VALUES (matched.customer_id, 'credit', matched.amount, new_balance, 'Vodafone Cash auto-confirmation', COALESCE(matched.transaction_reference, 'SMS-' || EXTRACT(EPOCH FROM NOW())::TEXT));

  -- Update request status
  UPDATE wallet_topup_requests
  SET status = 'approved', processed_at = NOW(), notes = 'Auto-approved from SMS sender ' || sender_phone
  WHERE id = matched.id;

  -- Notify customer
  INSERT INTO notifications (user_id, type, title, body)
  VALUES (matched.customer_id, 'wallet_topup', 'تم شحن رصيدك تلقائياً', 'تمت إضافة ' || matched.amount || ' Credit إلى محفظتك. رصيدك الآن: ' || new_balance || ' Credit.');

  RETURN jsonb_build_object('ok', true, 'request_id', matched.id, 'new_balance', new_balance);
END;
$$;
