package com.naderai.smsreader

import android.util.Log

/**
 * سجلات منظّمة لمسار الطلب بالكامل — Phase-3.
 * كل حدث يحمل: event_id + order_id + order_number + trace_id + device_id
 *                 + timestamp + status + duration_ms + result + reason + retry_count
 * لا تُسجَّل بيانات حسّاسة (كلمات مرور، OTP، بطاقات).
 */
object OrderEventLogger {

    private const val TAG = "OrderLifecycle"
    private var deviceIdProvider: () -> String? = { null }

    // مخزن trace_id لكل orderId — يبقى ثابتاً طوال عمر الطلب
    private val traceMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(provider: () -> String?) { deviceIdProvider = provider }
    private fun deviceId(): String? = deviceIdProvider()

    /** يحصل على trace_id الطلب أو يُنشئ واحداً جديداً */
    fun getOrBuildTrace(orderId: String?): String {
        if (orderId.isNullOrEmpty()) return "no-trace"
        return traceMap.getOrPut(orderId) { OrderDiagnosticsLog.buildTraceId(orderId) }
    }

    /** إزالة trace_id بعد وصول الطلب لحالة نهائية */
    fun releaseTrace(orderId: String?) { if (!orderId.isNullOrEmpty()) traceMap.remove(orderId) }

    fun log(
        event: String,
        orderId: String?,
        orderNumber: Long?,
        deviceId: String?,
        status: String?,
        details: String? = null,
        traceId: String? = null,
        durationMs: Long? = null,
        retryCount: Int = 0,
    ) {
        val resolvedTrace = traceId ?: getOrBuildTrace(orderId)
        val did = deviceId ?: deviceIdProvider()
        val parts = mutableListOf(
            "event=$event",
            "trace=$resolvedTrace",
        )
        if (!orderId.isNullOrEmpty()) parts.add("order_id=$orderId")
        if (orderNumber != null) parts.add("order_number=$orderNumber")
        if (!did.isNullOrEmpty()) parts.add("device_id=$did")
        if (!status.isNullOrEmpty()) parts.add("status=$status")
        if (durationMs != null) parts.add("duration_ms=$durationMs")
        if (retryCount > 0) parts.add("retry_count=$retryCount")
        if (!details.isNullOrEmpty()) parts.add("details=$details")
        parts.add("ts=${System.currentTimeMillis()}")
        Log.i(TAG, parts.joinToString(" | "))
    }

    // ── Lifecycle events ──────────────────────────────────────────────────────

    fun orderCreated(orderId: String?, orderNumber: Long?, status: String?) {
        val trace = getOrBuildTrace(orderId)
        log("ORDER_CREATED", orderId, orderNumber, null, status, traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RECEIVED, orderNumber, orderId,
            traceId = trace, details = "status=$status")
    }

    fun orderEligible(orderId: String?, orderNumber: Long?, status: String?) =
        log("ORDER_ELIGIBLE_FOR_READER", orderId, orderNumber, null, status,
            traceId = getOrBuildTrace(orderId))

    fun dispatchAttempt(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCH_ATTEMPT", orderId, orderNumber, deviceId, null,
            details = "sending to device", traceId = getOrBuildTrace(orderId))

    fun orderDispatched(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCHED", orderId, orderNumber, deviceId, null,
            traceId = getOrBuildTrace(orderId))

    fun syncRequest(reason: String, deviceId: String? = null) =
        log("ANDROID_SYNC_REQUEST", null, null, deviceId, null, reason)

    fun syncResponse(orderCount: Int, taskCount: Int, deviceId: String? = null) {
        log("ANDROID_SYNC_RESPONSE", null, null, deviceId, null, "orders=$orderCount tasks=$taskCount")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SYNC_TASKS,
            details = "orders=$orderCount tasks=$taskCount")
    }

    fun orderDelivered(orderId: String?, orderNumber: Long?, deviceId: String?, status: String?) {
        val trace = getOrBuildTrace(orderId)
        log("ORDER_DELIVERED_TO_DEVICE", orderId, orderNumber, deviceId, status, traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RECEIVED, orderNumber, orderId,
            traceId = trace, details = "heartbeat → status=$status")
    }

    fun orderAcknowledged(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_ACKNOWLEDGED", orderId, orderNumber, deviceId, null, traceId = getOrBuildTrace(orderId))

    fun scanStarted(orderId: String?, orderNumber: Long?, taskId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("SMS_SCAN_STARTED", orderId, orderNumber, null, null, "task_id=$taskId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_STARTED, orderNumber, orderId, taskId,
            traceId = trace)
    }

    fun matchFound(orderId: String?, orderNumber: Long?, transactionId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("SMS_MATCH_FOUND", orderId, orderNumber, null, null, "transaction_id=$transactionId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_MATCH_FOUND, orderNumber, orderId,
            traceId = trace, details = "tx=$transactionId")
    }

    fun verificationSubmitted(orderId: String?, orderNumber: Long?, status: String?) {
        val trace = getOrBuildTrace(orderId)
        log("VERIFICATION_SUBMITTED", orderId, orderNumber, null, status, traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_SEND_START, orderNumber, orderId,
            traceId = trace, details = "status=$status")
    }

    fun orderConfirmed(orderId: String?, orderNumber: Long?, transactionId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("ORDER_CONFIRMED", orderId, orderNumber, null, "transaction_id=$transactionId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK, orderNumber, orderId,
            traceId = trace, details = "tx=$transactionId")
        releaseTrace(orderId) // حالة نهائية — حرر trace
    }

    fun orderRejected(orderId: String?, orderNumber: Long?, reason: String?) {
        val trace = getOrBuildTrace(orderId)
        log("ORDER_REJECTED", orderId, orderNumber, null, reason, traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL, orderNumber, orderId,
            traceId = trace, details = reason)
        releaseTrace(orderId)
    }

    fun duplicateIgnored(orderId: String?, orderNumber: Long?, taskId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("DUPLICATE_IGNORED", orderId, orderNumber, null, null, "task_id=$taskId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_SKIPPED, orderNumber, orderId, taskId,
            traceId = trace, details = "duplicate task_id")
    }

    fun terminalIgnored(orderId: String?, orderNumber: Long?, status: String?) {
        log("TERMINAL_IGNORED", orderId, orderNumber, null, status, traceId = getOrBuildTrace(orderId))
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.TERMINAL_IGNORED, orderNumber, orderId,
            traceId = getOrBuildTrace(orderId), details = "local_status=$status")
    }

    fun staleDeviceReassigned(reassignedCount: Int, deviceId: String?) =
        log("STALE_DEVICE_REASSIGNED", null, null, deviceId, null, "reassigned_count=$reassignedCount")

    // ── أحداث SMS ──────────────────────────────────────────────────────────────

    fun scanNotFound(orderId: String?, orderNumber: Long?, taskId: String?, reason: String? = null) {
        val trace = getOrBuildTrace(orderId)
        log("SMS_NOT_FOUND", orderId, orderNumber, null, null, "task_id=$taskId reason=$reason", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_NOT_FOUND, orderNumber, orderId, taskId,
            traceId = trace, details = reason)
    }

    fun scanAmountMismatch(orderId: String?, orderNumber: Long?, taskId: String?, found: Double, expected: Double) {
        val trace = getOrBuildTrace(orderId)
        log("SMS_AMOUNT_MISMATCH", orderId, orderNumber, null, null, "found=$found expected=$expected", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_AMOUNT_MISMATCH, orderNumber, orderId, taskId,
            traceId = trace, details = "وجد=$found مطلوب=$expected")
    }

    fun scanFailed(orderId: String?, orderNumber: Long?, taskId: String?, reason: String?) {
        val trace = getOrBuildTrace(orderId)
        log("SMS_SCAN_FAILED", orderId, orderNumber, null, null, "reason=$reason", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_SCAN_FAILED, orderNumber, orderId, taskId,
            traceId = trace, details = reason)
    }

    // ── أحداث السيرفر ──────────────────────────────────────────────────────────

    fun serverError(orderId: String?, orderNumber: Long?, code: Int, body: String?, details: String? = null,
                    retryCount: Int = 0) {
        val trace = getOrBuildTrace(orderId)
        log("SERVER_ERROR", orderId, orderNumber, null, null, "HTTP=$code ${details.orEmpty()}",
            traceId = trace, retryCount = retryCount)
        val type = when (code) {
            401, 403 -> OrderDiagnosticsLog.EventType.AUTH_ERROR
            0        -> OrderDiagnosticsLog.EventType.NETWORK_ERROR
            else     -> OrderDiagnosticsLog.EventType.SERVER_RESPONSE_ERROR
        }
        OrderDiagnosticsLog.log(type, orderNumber, orderId, traceId = trace, retryCount = retryCount,
            details = details ?: "HTTP=$code", serverCode = code, serverResponse = body)
    }

    fun serverSuccess(orderId: String?, orderNumber: Long?, code: Int, body: String?) {
        val trace = getOrBuildTrace(orderId)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK, orderNumber, orderId,
            traceId = trace, serverCode = code, serverResponse = body)
    }

    fun orderReset(orderId: String?, orderNumber: Long?, reason: String?) {
        log("ORDER_RESET", orderId, orderNumber, null, null, reason, traceId = getOrBuildTrace(orderId))
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RESET, orderNumber, orderId,
            traceId = getOrBuildTrace(orderId), details = reason)
    }

    fun scanFromCache(orderId: String?, orderNumber: Long?, taskId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("SCAN_FROM_CACHE", orderId, orderNumber, null, null, "task_id=$taskId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_CACHED, orderNumber, orderId, taskId,
            traceId = trace)
    }

    fun scanLocked(orderId: String?, orderNumber: Long?, taskId: String?) {
        val trace = getOrBuildTrace(orderId)
        log("SCAN_LOCKED", orderId, orderNumber, null, null, "task_id=$taskId", traceId = trace)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_LOCKED, orderNumber, orderId, taskId,
            traceId = trace)
    }

    fun heartbeatTasks(taskCount: Int) {
        log("HEARTBEAT_TASKS", null, null, null, null, "count=$taskCount")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.HEARTBEAT_TASKS, details = "count=$taskCount")
    }
}
