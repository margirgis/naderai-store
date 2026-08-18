-- SMS transaction safety: server-side uniqueness and audit storage.
-- This migration is intentionally additive; the existing confirmation RPCs should
-- be updated to write transaction_id into sms_transaction_receipts before crediting.

CREATE TABLE IF NOT EXISTS sms_transaction_receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id text NOT NULL,
  sender_phone text,
  sender_name text,
  amount numeric(12,2) NOT NULL,
  receiver_wallet text,
  sms_body text,
  device_id text,
  payment_order_id uuid,
  topup_request_id uuid,
  status text NOT NULL DEFAULT 'accepted'
    CHECK (status IN ('accepted','rejected','duplicate')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS sms_transaction_receipts_transaction_id_unique
  ON sms_transaction_receipts (transaction_id)
  WHERE transaction_id IS NOT NULL AND transaction_id <> '';

CREATE INDEX IF NOT EXISTS sms_transaction_receipts_payment_order_idx
  ON sms_transaction_receipts (payment_order_id);

CREATE INDEX IF NOT EXISTS sms_transaction_receipts_topup_request_idx
  ON sms_transaction_receipts (topup_request_id);

ALTER TABLE sms_transaction_receipts ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "admin_read_sms_transaction_receipts" ON sms_transaction_receipts;
CREATE POLICY "admin_read_sms_transaction_receipts"
  ON sms_transaction_receipts
  FOR SELECT TO authenticated
  USING (is_admin());

COMMENT ON TABLE sms_transaction_receipts IS
  'Server-side audit and uniqueness ledger for Vodafone Cash transaction IDs. Confirmation RPCs must reserve the transaction ID here before crediting.';
COMMENT ON COLUMN sms_transaction_receipts.transaction_id IS
  'Vodafone Cash transaction/reference ID; globally unique for accepted or reserved transactions.';
