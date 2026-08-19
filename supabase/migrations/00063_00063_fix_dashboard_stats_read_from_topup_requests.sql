
-- ================================================================
-- Migration 00063: Fix get_topup_dashboard_stats to read from
-- wallet_topup_requests (not payment_orders which is often empty)
-- ================================================================

CREATE OR REPLACE FUNCTION public.get_topup_dashboard_stats()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_scanning   INT;
  v_confirmed  INT;
  v_failed     INT;
  v_expired    INT;
  v_offline    INT;
  v_reopened   INT;
  v_rejected   INT;
  v_duplicate  INT;
  v_total      INT;
  v_online     BOOLEAN;
  v_last_beat  TIMESTAMPTZ;
  v_pending_q  INT;
BEGIN
  -- Read counts from wallet_topup_requests (source of truth)
  SELECT
    COUNT(*) FILTER (WHERE status IN ('scanning','pending','waiting_for_verification'))   AS scanning,
    COUNT(*) FILTER (WHERE status IN ('approved','confirmed'))                            AS confirmed,
    COUNT(*) FILTER (WHERE status = 'failed')                                             AS failed,
    COUNT(*) FILTER (WHERE status = 'expired')                                            AS expired,
    COUNT(*) FILTER (WHERE status = 'admin_offline')                                      AS offline,
    COUNT(*) FILTER (WHERE status = 'reopened')                                           AS reopened,
    COUNT(*) FILTER (WHERE status = 'rejected' AND scan_status != 'duplicate')            AS rejected,
    COUNT(*) FILTER (WHERE scan_status = 'duplicate')                                     AS duplicate,
    COUNT(*)                                                                               AS total
  INTO v_scanning, v_confirmed, v_failed, v_expired, v_offline, v_reopened, v_rejected, v_duplicate, v_total
  FROM wallet_topup_requests
  WHERE created_at >= now() - INTERVAL '48 hours';

  -- Device heartbeat
  SELECT last_heartbeat_at,
         last_heartbeat_at >= now() - INTERVAL '90 seconds'
  INTO v_last_beat, v_online
  FROM sms_device_status
  WHERE is_active = true
  ORDER BY last_heartbeat_at DESC
  LIMIT 1;

  -- Pending queue = طلبات تنتظر فحص الجهاز
  SELECT COUNT(*) INTO v_pending_q
  FROM wallet_topup_requests
  WHERE status IN ('pending','scanning','waiting_for_verification','admin_offline','reopened')
    AND created_at >= now() - INTERVAL '48 hours';

  RETURN jsonb_build_object(
    'scanning',             COALESCE(v_scanning, 0),
    'confirmed',            COALESCE(v_confirmed, 0),
    'failed',               COALESCE(v_failed, 0),
    'expired',              COALESCE(v_expired, 0),
    'admin_offline_count',  COALESCE(v_offline, 0),
    'reopened',             COALESCE(v_reopened, 0),
    'rejected',             COALESCE(v_rejected, 0),
    'duplicate',            COALESCE(v_duplicate, 0),
    'total',                COALESCE(v_total, 0),
    'device_online',        COALESCE(v_online, false),
    'last_heartbeat_at',    v_last_beat,
    'pending_queue',        COALESCE(v_pending_q, 0)
  );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_topup_dashboard_stats() TO authenticated;
