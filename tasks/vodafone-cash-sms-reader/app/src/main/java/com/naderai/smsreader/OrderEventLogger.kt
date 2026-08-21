package com.naderai.smsreader

import android.util.Log

/**
 * سجلات منظّمة لمسار الطلب بالكامل.
 * لا تُسجّل بيانات حسّاسة (كلمات مرور، OTP، بيانات بطاقات).
 * يتم تعيين معرّف الجهاز مرة واحدة من Application/Activity.
 * كل حدث يُحقن تلقائياً في OrderDiagnosticsLog لعرضه في شاشة المراقبة.
 */
object OrderEventLogger {

    private const val TAG = "OrderLifecycle"
    private var deviceIdProvider: () -> String? = { null }

    fun init(provider: () -> String?) {
        deviceIdProvider = provider
    }

    private fun deviceId(): String? = deviceIdProvider()

    fun log(event: String, orderId: String?, orderNumber: Long?, deviceId: String?, status: String?, details: String? = null) {
        val parts = mutableListOf("event=$event")
        if (!orderId.isNullOrEmpty()) parts.add("order_id=$orderId")
        if (orderNumber != null) parts.add("order_number=$orderNumber")
        val did = deviceId ?: deviceIdProvider()
        if (!did.isNullOrEmpty()) parts.add("device_id=$did")
        if (!status.isNullOrEmpty()) parts.add("status=$status")
        if (!details.isNullOrEmpty()) parts.add("details=$details")
        parts.add("ts=${System.currentTimeMillis()}")
        Log.i(TAG, parts.joinToString(" | "))
    }

    fun orderCreated(orderId: String?, orderNumber: Long?, status: String?) {
        log("ORDER_CREATED", orderId, orderNumber, null, status)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RECEIVED, orderNumber, orderId, details = "status=$status")
    }

    fun orderEligible(orderId: String?, orderNumber: Long?, status: String?) =
        log("ORDER_ELIGIBLE_FOR_READER", orderId, orderNumber, null, status)

    fun dispatchAttempt(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCH_ATTEMPT", orderId, orderNumber, deviceId, null, "sending to device")

    fun orderDispatched(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCHED", orderId, orderNumber, deviceId, null)

    fun syncRequest(reason: String, deviceId: String? = null) =
        log("ANDROID_SYNC_REQUEST", null, null, deviceId, null, reason)

    fun syncResponse(orderCount: Int, taskCount: Int, deviceId: String? = null) {
        log("ANDROID_SYNC_RESPONSE", null, null, deviceId, null, "orders=$orderCount tasks=$taskCount")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SYNC_TASKS, details = "orders=$orderCount tasks=$taskCount")
    }

    fun orderDelivered(orderId: String?, orderNumber: Long?, deviceId: String?, status: String?) {
        log("ORDER_DELIVERED_TO_DEVICE", orderId, orderNumber, deviceId, status)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RECEIVED, orderNumber, orderId, details = "heartbeat → status=$status")
    }

    fun orderAcknowledged(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_ACKNOWLEDGED", orderId, orderNumber, deviceId, null)

    fun scanStarted(orderId: String?, orderNumber: Long?, taskId: String?) {
        log("SMS_SCAN_STARTED", orderId, orderNumber, null, null, "task_id=$taskId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_STARTED, orderNumber, orderId, taskId)
    }

    fun matchFound(orderId: String?, orderNumber: Long?, transactionId: String?) {
        log("SMS_MATCH_FOUND", orderId, orderNumber, null, null, "transaction_id=$transactionId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_MATCH_FOUND, orderNumber, orderId, details = "tx=$transactionId")
    }

    fun verificationSubmitted(orderId: String?, orderNumber: Long?, status: String?) {
        log("VERIFICATION_SUBMITTED", orderId, orderNumber, null, status)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_SEND_START, orderNumber, orderId, details = "status=$status")
    }

    fun orderConfirmed(orderId: String?, orderNumber: Long?, transactionId: String?) {
        log("ORDER_CONFIRMED", orderId, orderNumber, null, "transaction_id=$transactionId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK, orderNumber, orderId, details = "tx=$transactionId")
    }

    fun orderRejected(orderId: String?, orderNumber: Long?, reason: String?) {
        log("ORDER_REJECTED", orderId, orderNumber, null, reason)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL, orderNumber, orderId, details = reason)
    }

    fun duplicateIgnored(orderId: String?, orderNumber: Long?, taskId: String?) {
        log("DUPLICATE_IGNORED", orderId, orderNumber, null, null, "task_id=$taskId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_SKIPPED, orderNumber, orderId, taskId, details = "duplicate task_id")
    }

    fun terminalIgnored(orderId: String?, orderNumber: Long?, status: String?) {
        log("TERMINAL_IGNORED", orderId, orderNumber, null, status)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.TERMINAL_IGNORED, orderNumber, orderId, details = "local_status=$status")
    }

    fun staleDeviceReassigned(reassignedCount: Int, deviceId: String?) =
        log("STALE_DEVICE_REASSIGNED", null, null, deviceId, null, "reassigned_count=$reassignedCount")

    // ── أحداث إضافية للمراقبة المباشرة ──────────────────────────────────

    fun scanNotFound(orderId: String?, orderNumber: Long?, taskId: String?, reason: String? = null) {
        log("SMS_NOT_FOUND", orderId, orderNumber, null, null, "task_id=$taskId reason=$reason")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_NOT_FOUND, orderNumber, orderId, taskId, details = reason)
    }

    fun scanAmountMismatch(orderId: String?, orderNumber: Long?, taskId: String?, found: Double, expected: Double) {
        log("SMS_AMOUNT_MISMATCH", orderId, orderNumber, null, null, "found=$found expected=$expected")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_AMOUNT_MISMATCH, orderNumber, orderId, taskId, details = "وجد=$found مطلوب=$expected")
    }

    fun scanFailed(orderId: String?, orderNumber: Long?, taskId: String?, reason: String?) {
        log("SMS_SCAN_FAILED", orderId, orderNumber, null, null, "reason=$reason")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SMS_SCAN_FAILED, orderNumber, orderId, taskId, details = reason)
    }

    fun serverError(orderId: String?, orderNumber: Long?, code: Int, body: String?, details: String? = null) {
        log("SERVER_ERROR", orderId, orderNumber, null, null, "HTTP=$code ${details.orEmpty()}")
        val type = when (code) {
            401, 403 -> OrderDiagnosticsLog.EventType.AUTH_ERROR
            0        -> OrderDiagnosticsLog.EventType.NETWORK_ERROR
            else     -> OrderDiagnosticsLog.EventType.SERVER_RESPONSE_ERROR
        }
        OrderDiagnosticsLog.log(type, orderNumber, orderId, details = details ?: "HTTP=$code", serverCode = code, serverResponse = body)
    }

    fun serverSuccess(orderId: String?, orderNumber: Long?, code: Int, body: String?) {
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK, orderNumber, orderId, serverCode = code, serverResponse = body)
    }

    fun orderReset(orderId: String?, orderNumber: Long?, reason: String?) {
        log("ORDER_RESET", orderId, orderNumber, null, null, reason)
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.ORDER_RESET, orderNumber, orderId, details = reason)
    }

    fun scanFromCache(orderId: String?, orderNumber: Long?, taskId: String?) {
        log("SCAN_FROM_CACHE", orderId, orderNumber, null, null, "task_id=$taskId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_CACHED, orderNumber, orderId, taskId)
    }

    fun scanLocked(orderId: String?, orderNumber: Long?, taskId: String?) {
        log("SCAN_LOCKED", orderId, orderNumber, null, null, "task_id=$taskId")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SCAN_LOCKED, orderNumber, orderId, taskId)
    }

    fun heartbeatTasks(taskCount: Int) {
        log("HEARTBEAT_TASKS", null, null, null, null, "count=$taskCount")
        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.HEARTBEAT_TASKS, details = "count=$taskCount")
    }
}
