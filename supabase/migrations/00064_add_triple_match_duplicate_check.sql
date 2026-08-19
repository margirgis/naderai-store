
-- ── Migration 00064: Triple-match duplicate check ──────────────────────────
-- Problem: confirm_payment_order only checks transaction_id for cross-order duplicates.
-- A transaction_id can theoretically repeat across different payment providers.
-- Fix: if tx_id + sender_phone + amount ALL match a previously confirmed transaction
--      → treat as duplicate regardless of provider.
-- This is additive — the existing tx_id-only check still runs first (fast path).

-- Add composite index to confirmed_transactions for fast triple-match lookup
CREATE INDEX IF NOT EXISTS idx_confirmed_tx_triple
  ON confirmed_transactions (transaction_id, sender_phone, amount);

-- Add composite index to payment_transactions for same
CREATE INDEX IF NOT EXISTS idx_payment_tx_triple
  ON payment_transactions (transaction_id, sender_phone, amount)
  WHERE status = 'accepted';

-- Helper function: checks triple match (tx_id + phone + amount) across confirmed orders
CREATE OR REPLACE FUNCTION is_triple_duplicate(
  p_transaction_id TEXT,
  p_sender_phone   TEXT,
  p_amount         NUMERIC,
  p_order_id       UUID DEFAULT NULL  -- exclude current order from idempotency check
)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
  v_phone TEXT := normalize_egyptian_phone(COALESCE(p_sender_phone, ''));
BEGIN
  -- Skip weak/auto-generated IDs
  IF p_transaction_id IS NULL OR p_transaction_id = ''
     OR p_transaction_id LIKE 'DEVICE-%'
     OR p_transaction_id LIKE 'SMS-%'
     OR p_transaction_id LIKE 'AUTO-%'
  THEN
    RETURN FALSE;
  END IF;

  -- Check confirmed_transactions: same tx + same phone + same amount used for a DIFFERENT order
  IF EXISTS (
    SELECT 1 FROM confirmed_transactions ct
    WHERE ct.transaction_id = p_transaction_id
      AND ROUND(ct.amount::numeric, 2) = ROUND(p_amount::numeric, 2)
      AND (
        v_phone = '' OR v_phone IS NULL
        OR normalize_egyptian_phone(COALESCE(ct.sender_phone, '')) = v_phone
      )
      AND (p_order_id IS NULL OR ct.order_id <> p_order_id)
  ) THEN
    RETURN TRUE;
  END IF;

  -- Check payment_transactions ledger: same triple, accepted, different order
  IF EXISTS (
    SELECT 1 FROM payment_transactions pt
    WHERE pt.transaction_id = p_transaction_id
      AND pt.status = 'accepted'
      AND ROUND(pt.amount::numeric, 2) = ROUND(p_amount::numeric, 2)
      AND (
        v_phone = '' OR v_phone IS NULL
        OR normalize_egyptian_phone(COALESCE(pt.sender_phone, '')) = v_phone
      )
      AND (p_order_id IS NULL OR pt.order_id <> p_order_id)
  ) THEN
    RETURN TRUE;
  END IF;

  RETURN FALSE;
END;
$$;

COMMENT ON FUNCTION is_triple_duplicate IS
  'Returns TRUE if (transaction_id + sender_phone + amount) all match a previously confirmed transaction for a different order. Prevents cross-provider transaction_id collision fraud.';
