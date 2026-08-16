package com.naderai.smsreader

import android.util.Log

/**
 * سجلات منظّمة لمسار الطلب بالكامل.
 * لا تُسجّل بيانات حسّاسة (كلمات مرور، OTP، بيانات بطاقات).
 * يتم تعيين معرّف الجهاز مرة واحدة من Application/Activity.
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

    fun orderCreated(orderId: String?, orderNumber: Long?, status: String?) =
        log("ORDER_CREATED", orderId, orderNumber, null, status)

    fun orderEligible(orderId: String?, orderNumber: Long?, status: String?) =
        log("ORDER_ELIGIBLE_FOR_READER", orderId, orderNumber, null, status)

    fun dispatchAttempt(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCH_ATTEMPT", orderId, orderNumber, deviceId, null, "sending to device")

    fun orderDispatched(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_DISPATCHED", orderId, orderNumber, deviceId, null)

    fun syncRequest(reason: String, deviceId: String? = null) =
        log("ANDROID_SYNC_REQUEST", null, null, deviceId, null, reason)

    fun syncResponse(orderCount: Int, taskCount: Int, deviceId: String? = null) =
        log("ANDROID_SYNC_RESPONSE", null, null, deviceId, null, "orders=$orderCount tasks=$taskCount")

    fun orderDelivered(orderId: String?, orderNumber: Long?, deviceId: String?, status: String?) =
        log("ORDER_DELIVERED_TO_DEVICE", orderId, orderNumber, deviceId, status)

    fun orderAcknowledged(orderId: String?, orderNumber: Long?, deviceId: String?) =
        log("ORDER_ACKNOWLEDGED", orderId, orderNumber, deviceId, null)

    fun scanStarted(orderId: String?, orderNumber: Long?, taskId: String?) =
        log("SMS_SCAN_STARTED", orderId, orderNumber, null, null, "task_id=$taskId")

    fun matchFound(orderId: String?, orderNumber: Long?, transactionId: String?) =
        log("SMS_MATCH_FOUND", orderId, orderNumber, null, null, "transaction_id=$transactionId")

    fun verificationSubmitted(orderId: String?, orderNumber: Long?, status: String?) =
        log("VERIFICATION_SUBMITTED", orderId, orderNumber, null, status)

    fun orderConfirmed(orderId: String?, orderNumber: Long?, transactionId: String?) =
        log("ORDER_CONFIRMED", orderId, orderNumber, null, "transaction_id=$transactionId")

    fun orderRejected(orderId: String?, orderNumber: Long?, reason: String?) =
        log("ORDER_REJECTED", orderId, orderNumber, null, reason)

    fun duplicateIgnored(orderId: String?, orderNumber: Long?, taskId: String?) =
        log("DUPLICATE_IGNORED", orderId, orderNumber, null, null, "task_id=$taskId")

    fun terminalIgnored(orderId: String?, orderNumber: Long?, status: String?) =
        log("TERMINAL_IGNORED", orderId, orderNumber, null, status)

    fun staleDeviceReassigned(reassignedCount: Int, deviceId: String?) =
        log("STALE_DEVICE_REASSIGNED", null, null, deviceId, null, "reassigned_count=$reassignedCount")
}
