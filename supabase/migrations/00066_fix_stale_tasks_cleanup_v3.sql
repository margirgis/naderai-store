
-- ═══════════════════════════════════════════════════════════════════
-- 1: توسيع result_status constraint
-- ═══════════════════════════════════════════════════════════════════
ALTER TABLE pending_tasks
  DROP CONSTRAINT IF EXISTS pending_tasks_result_status_check;

ALTER TABLE pending_tasks
  ADD CONSTRAINT pending_tasks_result_status_check
  CHECK (result_status IN (
    'success','failure','not_found','expired','amount_mismatch','stale_cleanup'
  ));

-- ═══════════════════════════════════════════════════════════════════
-- 2: تنظيف الـ stale tasks
-- ═══════════════════════════════════════════════════════════════════
UPDATE pending_tasks pt
SET task_status='completed', result_status='stale_cleanup',
    failure_reason='الطلب انتهى أو رُفض قبل إتمام الفحص',
    completed_at=now(), updated_at=now()
FROM wallet_topup_requests wtr
WHERE wtr.id = pt.request_id
  AND pt.task_status IN ('assigned','pending')
  AND wtr.status IN ('rejected','approved','expired')
  AND pt.id != 'bbae431c-616e-460c-8706-fef468cb171e';

-- ═══════════════════════════════════════════════════════════════════
-- 3: index للـ heartbeat query
-- ═══════════════════════════════════════════════════════════════════
CREATE INDEX IF NOT EXISTS idx_pending_tasks_active_device
  ON pending_tasks (device_id, task_status)
  WHERE task_status IN ('assigned','pending');

-- ═══════════════════════════════════════════════════════════════════
-- 4: دالة تنظيف تلقائي (بدون GET DIAGNOSTICS accumulation)
-- ═══════════════════════════════════════════════════════════════════
CREATE OR REPLACE FUNCTION cleanup_stale_pending_tasks()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  c1 integer;
  c2 integer;
BEGIN
  UPDATE pending_tasks
  SET task_status='completed', result_status='expired',
      failure_reason='انتهت صلاحية الطلب',
      completed_at=now(), updated_at=now()
  WHERE task_status IN ('assigned','pending')
    AND order_expires_at IS NOT NULL
    AND now() > order_expires_at;
  GET DIAGNOSTICS c1 = ROW_COUNT;

  UPDATE pending_tasks pt
  SET task_status='completed', result_status='stale_cleanup',
      failure_reason='الطلب انتهى أو رُفض',
      completed_at=now(), updated_at=now()
  FROM wallet_topup_requests wtr
  WHERE wtr.id = pt.request_id
    AND pt.task_status IN ('assigned','pending')
    AND wtr.status IN ('rejected','approved','expired');
  GET DIAGNOSTICS c2 = ROW_COUNT;

  RETURN c1 + c2;
END;
$$;
