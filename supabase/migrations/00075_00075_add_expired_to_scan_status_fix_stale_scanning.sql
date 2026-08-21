
-- 1. إضافة 'expired' لـ scan_status check constraint
ALTER TABLE wallet_topup_requests
  DROP CONSTRAINT IF EXISTS wallet_topup_requests_scan_status_check;

ALTER TABLE wallet_topup_requests
  ADD CONSTRAINT wallet_topup_requests_scan_status_check
  CHECK (scan_status = ANY (ARRAY[
    'pending','scanning','verified','approved','rejected',
    'manual_review','duplicate','amount_mismatch',
    'waiting_for_verification','confirmed','not_found',
    'sender_phone_mismatch','failed','expired'
  ]));

-- 2. إصلاح الطلب العالق في scanning (انتهت صلاحيته)
UPDATE payment_orders
SET status = 'expired',
    failure_reason = 'انتهت الصلاحية — phantom scan reset',
    updated_at = now()
WHERE id = '80e6733a-3098-4391-a06a-4e58fdf8a3f1'
  AND status NOT IN ('confirmed','cancelled','duplicate');

UPDATE wallet_topup_requests
SET status = 'rejected',
    scan_status = 'expired',
    failure_reason = 'payment_order انتهت صلاحيته',
    updated_at = now()
WHERE payment_order_id = '80e6733a-3098-4391-a06a-4e58fdf8a3f1'
  AND status NOT IN ('approved');

UPDATE pending_tasks
SET task_status = 'completed',
    result_status = 'failure',
    failure_reason = 'payment_order انتهت صلاحيته',
    completed_at = now(),
    updated_at = now()
WHERE payment_order_id = '80e6733a-3098-4391-a06a-4e58fdf8a3f1'
  AND task_status = 'assigned';

-- 3. إصلاح عام: أي طلب scanning انتهت صلاحيته يُنظَّف
UPDATE wallet_topup_requests wtr
SET status = 'rejected',
    scan_status = 'expired',
    failure_reason = 'payment_order انتهت صلاحيته (batch cleanup)',
    updated_at = now()
FROM payment_orders po
WHERE wtr.payment_order_id = po.id
  AND po.status = 'expired'
  AND wtr.status IN ('scanning','pending')
  AND wtr.scan_status NOT IN ('approved','confirmed');

UPDATE pending_tasks pt
SET task_status = 'completed',
    result_status = 'failure',
    failure_reason = 'payment_order منتهي الصلاحية (batch cleanup)',
    completed_at = now(),
    updated_at = now()
FROM payment_orders po
WHERE pt.payment_order_id = po.id
  AND po.status IN ('expired','cancelled')
  AND pt.task_status = 'assigned';
