-- wallet_topup_requests: customer-initiated wallet top-up requests
CREATE TABLE wallet_topup_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id uuid NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
  amount numeric(12,4) NOT NULL,
  status text NOT NULL DEFAULT 'pending',
  payment_method text NOT NULL DEFAULT 'vodafone_cash',
  sender_phone text,
  transaction_reference text,
  notes text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  processed_by uuid REFERENCES profiles(id)
);

CREATE INDEX wallet_topup_requests_customer_id_idx ON wallet_topup_requests(customer_id);
CREATE INDEX wallet_topup_requests_status_idx ON wallet_topup_requests(status);

ALTER TABLE wallet_topup_requests ENABLE ROW LEVEL SECURITY;

-- Helper already exists: is_admin()
CREATE POLICY "customer_select_own_topup_requests" ON wallet_topup_requests
  FOR SELECT TO authenticated
  USING (customer_id = auth.uid() OR is_admin());

CREATE POLICY "customer_insert_own_topup_requests" ON wallet_topup_requests
  FOR INSERT TO authenticated
  WITH CHECK (customer_id = auth.uid());

CREATE POLICY "admin_update_topup_requests" ON wallet_topup_requests
  FOR UPDATE TO authenticated
  USING (is_admin())
  WITH CHECK (is_admin());

-- Trigger to auto-update updated_at
CREATE OR REPLACE FUNCTION set_updated_at_wallet_topup_requests()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$;

CREATE TRIGGER wallet_topup_requests_updated_at
  BEFORE UPDATE ON wallet_topup_requests
  FOR EACH ROW EXECUTE FUNCTION set_updated_at_wallet_topup_requests();

-- Admin notification helper when new top-up request created
CREATE OR REPLACE FUNCTION notify_admin_wallet_topup_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO notifications (user_id, type, title, body, order_id)
  SELECT id, 'wallet_topup_request', 'طلب شحن محفظة جديد', 
         'عميل يطلب شحن ' || NEW.amount || ' Credit',
         NULL
  FROM profiles WHERE role = 'admin';
  RETURN NEW;
END;
$$;

CREATE TRIGGER wallet_topup_request_notify_admin
  AFTER INSERT ON wallet_topup_requests
  FOR EACH ROW EXECUTE FUNCTION notify_admin_wallet_topup_request();
