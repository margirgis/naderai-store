
-- Migration 00061: Fix admin RLS on wallet_topup_requests
-- Problem: admin_all_wallet_topup_requests was created for 'public' role
--          but admin users connect as 'authenticated' → policy never applies
-- Fix: drop old policies, recreate with TO authenticated + SECURITY DEFINER helper

-- ── Step 1: Drop conflicting policies ────────────────────────────────────────
DROP POLICY IF EXISTS admin_all_wallet_topup_requests   ON public.wallet_topup_requests;
DROP POLICY IF EXISTS admin_update_topup_requests       ON public.wallet_topup_requests;
DROP POLICY IF EXISTS customer_select_own_topup_requests ON public.wallet_topup_requests;

-- ── Step 2: Recreate SELECT — customer sees own, admin sees all ───────────────
CREATE POLICY admin_customer_select_topup_requests
  ON public.wallet_topup_requests
  FOR SELECT
  TO authenticated
  USING (
    customer_id = auth.uid()
    OR EXISTS (
      SELECT 1 FROM profiles
      WHERE id = auth.uid() AND role = 'admin'
    )
  );

-- ── Step 3: Admin UPDATE (already authenticated, keep is_admin()) ─────────────
CREATE POLICY admin_update_topup_requests
  ON public.wallet_topup_requests
  FOR UPDATE
  TO authenticated
  USING ( is_admin() )
  WITH CHECK ( is_admin() );

-- ── Step 4: Admin DELETE (needed for cleanup operations) ──────────────────────
CREATE POLICY admin_delete_topup_requests
  ON public.wallet_topup_requests
  FOR DELETE
  TO authenticated
  USING ( is_admin() );

-- ── Verify ────────────────────────────────────────────────────────────────────
SELECT policyname, cmd, roles, qual
FROM pg_policies
WHERE tablename = 'wallet_topup_requests'
ORDER BY policyname;
