-- Vodafone Cash contract:
-- receiver_wallet = 01097273680 (fixed receiving wallet)
-- sender_phone    = wallet that actually sent the transfer.
-- Never use customer_phone or receiver_wallet as sender_phone.

INSERT INTO system_settings (key, value)
VALUES ('vodafone_cash_number', '01097273680')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

-- Server-side uniqueness for real Vodafone Cash transaction IDs.
CREATE UNIQUE INDEX IF NOT EXISTS ux_confirmed_transactions_transaction_id
ON confirmed_transactions (transaction_id)
WHERE transaction_id IS NOT NULL;

-- Realtime is supplemental; Android heartbeat/polling remains recovery.
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE payment_orders;
  EXCEPTION WHEN duplicate_object THEN NULL; WHEN others THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE pending_tasks;
  EXCEPTION WHEN duplicate_object THEN NULL; WHEN others THEN NULL;
  END;
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE wallet_topup_requests;
  EXCEPTION WHEN duplicate_object THEN NULL; WHEN others THEN NULL;
  END;
END $$;
