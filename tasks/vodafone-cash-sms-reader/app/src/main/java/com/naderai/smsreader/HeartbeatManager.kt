package com.naderai.smsreader
import com.naderai.smsreader.BuildConfig

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build

class HeartbeatManager(
    private val context: Context,
    private val webhookUrl: String,
    private val secret: String,
    private val onStatusChange: (Boolean, String) -> Unit,
    private val onPendingTasks: (List<TaskScanner.Task>) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId: String get() = Companion.getDeviceId(context)
    private var wasConnected = false
    private var registered = false

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 120_000L
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_DEVICE_ID = "device_id"

        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: System.currentTimeMillis().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        // Always register first, then start heartbeat loop
        registerDevice()
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    /** Explicit device_register call — separate from heartbeat */
    fun registerDevice() {
        val payload = mapOf(
            "action" to "device_register",
            "device_id" to deviceId,
            "device_model" to (Build.MODEL ?: "Unknown"),
            "device_name" to (Build.DEVICE ?: "Unknown"),
            "android_version" to (Build.VERSION.RELEASE ?: "Unknown"),
            "app_version" to BuildConfig.VERSION_NAME,
            "phone_number" to "",
            "capabilities" to mapOf(
                "sms_read" to true,
                "sms_receive" to true,
                "realtime_scan" to true
            )
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { success, message, responseBody ->
            registered = success
            if (success) {
                android.util.Log.d("HeartbeatManager", "Device registered: $responseBody")
                AppState.updateRegistrationStatus(true, "مسجل ✓")
                // AppState.addNotification(DeviceNotification(
                //     title = "تم التسجيل بنجاح",
                //     message = "الجهاز مسجل في السيرفر",
                //     type = NotificationType.CONNECTED
                // ))
                // نكتفي بتحديث حالة التسجيل بدون إشعار كل مرة
                // Immediately send first heartbeat after registration
                sendHeartbeat()
            } else {
                AppState.updateRegistrationStatus(false, "فشل التسجيل: $message")
                AppState.addNotification(DeviceNotification(
                    title = "فشل التسجيل",
                    message = message,
                    type = NotificationType.ERROR
                ))
                android.util.Log.e("HeartbeatManager", "DEVICE_REGISTRATION_FAILED: $message")
            }
        }
    }

    private fun sendHeartbeat() {
        val payload = mapOf(
            "action" to "heartbeat",
            "device_id" to deviceId,
            "device_model" to (Build.MODEL ?: "Unknown"),
            "device_name" to (Build.DEVICE ?: "Unknown"),
            "android_version" to (Build.VERSION.RELEASE ?: "Unknown"),
            "app_version" to BuildConfig.VERSION_NAME,
            "phone_number" to "",
            "capabilities" to mapOf(
                "sms_read" to true,
                "sms_receive" to true,
                "realtime_scan" to true
            )
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { success, message, responseBody ->
            val justReconnected = !wasConnected && success
            wasConnected = success
            onStatusChange(success, if (success) "متصل" else "غير متصل: $message")
            AppState.updateFromHeartbeat(success, if (success) "متصل بالسيرفر" else "غير متصل: $message")
            if (success) {
                parseHeartbeatResponse(responseBody)
                if (justReconnected) {
                    AppState.addNotification(DeviceNotification(
                        title = "عادت الاتصال",
                        message = "تم استعادة الاتصال بالسيرفر",
                        type = NotificationType.CONNECTED
                    ))
                    RetryQueue.drainOnReconnect(context, webhookUrl, secret)
                }
            } else {
                AppState.addNotification(DeviceNotification(
                    title = "انقطع الاتصال",
                    message = "REALTIME_SUBSCRIPTION_FAILED: $message",
                    type = NotificationType.ERROR
                ))
            }
        }
    }

    fun parseHeartbeatResponse(responseBody: String) {
        try {
            val json = org.json.JSONObject(responseBody)

            // معالجة الأوامر من السيرفر (server → android)
            if (json.has("commands")) {
                val cmds = json.getJSONArray("commands")
                for (i in 0 until cmds.length()) {
                    val cmd = cmds.getJSONObject(i)
                    handleServerCommand(cmd)
                }
            }

            // قراءة المهام المعلقة
            val tasksArr = when {
                json.has("pending_tasks") -> json.getJSONArray("pending_tasks")
                json.has("tasks") -> json.getJSONArray("tasks")
                else -> return
            }
            val tasks = mutableListOf<TaskScanner.Task>()
            for (i in 0 until tasksArr.length()) {
                val obj = tasksArr.getJSONObject(i)
                val requestId = obj.getString("request_id")
                val taskId = obj.getString("task_id")

                // ══════════════════════════════════════════════════════════════
                // حماية الحالات النهائية: لا نُعيد إرسال المهام التي انتهت سلفاً.
                // الحالات النهائية في AppState: CONFIRMED, NOT_FOUND, AMOUNT_MISMATCH, FAILED, DUPLICATE
                // ══════════════════════════════════════════════════════════════
                val existingOrder = AppState.getOrders().firstOrNull { it.requestId == requestId }
                val isTerminal = existingOrder != null && existingOrder.status in setOf(
                    OrderStatus.CONFIRMED,
                    OrderStatus.NOT_FOUND,
                    OrderStatus.AMOUNT_MISMATCH,
                    OrderStatus.FAILED,
                    OrderStatus.DUPLICATE
                )
                if (isTerminal) {
                    android.util.Log.d("HeartbeatManager",
                        "Skipping terminal order $requestId (status=${existingOrder?.status?.name})")
                    continue
                }

                val task = TaskScanner.Task(
                    taskId = taskId,
                    requestId = requestId,
                    amountRequested = obj.optDouble("amount_requested", 0.0),
                    senderPhoneRequested = obj.optString("sender_phone_requested").takeIf { it.isNotEmpty() },
                    senderNameRequested = obj.optString("sender_name_requested").takeIf { it.isNotEmpty() },
                    fingerprintAmount = if (obj.has("fingerprint_amount")) obj.optDouble("fingerprint_amount") else null,
                    creditsAmount = if (obj.has("credits_amount")) obj.optDouble("credits_amount") else null,
                    orderNumber = if (obj.has("order_number") && !obj.isNull("order_number")) obj.getLong("order_number") else null,
                    creditsRequested = if (obj.has("credits_requested") && !obj.isNull("credits_requested")) obj.getInt("credits_requested") else null,
                    customerEmail = obj.optString("customer_email").takeIf { it.isNotEmpty() },
                    customerPhone = obj.optString("customer_phone").takeIf { it.isNotEmpty() },
                    paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                    requestCreatedAt = obj.optString("request_created_at").takeIf { it.isNotEmpty() }
                )
                tasks.add(task)

                // تحديث أو إضافة الطلب في AppState — addOrUpdateOrder يحمي الحالات النهائية
                AppState.addOrUpdateOrder(OrderItem(
                    requestId = requestId,
                    orderLabel = "طلب شحن",
                    expectedAmount = obj.optDouble("amount_requested", 0.0),
                    status = OrderStatus.PENDING,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    orderNumber = if (obj.has("order_number") && !obj.isNull("order_number")) obj.getLong("order_number") else null,
                    creditsRequested = if (obj.has("credits_requested") && !obj.isNull("credits_requested")) obj.getInt("credits_requested") else null,
                    customerEmail = obj.optString("customer_email").takeIf { it.isNotEmpty() },
                    customerPhone = obj.optString("customer_phone").takeIf { it.isNotEmpty() },
                    paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                    taskId = taskId
                ))

                // إشعار الطلبات الجديدة فقط (لم يكن موجوداً من قبل)
                if (existingOrder == null) {
                    val orderNum = if (obj.has("order_number") && !obj.isNull("order_number"))
                        "#${obj.getLong("order_number")}"
                    else "#${requestId.take(8)}"
                    val customerInfo = obj.optString("customer_phone").takeIf { it.isNotEmpty() }
                        ?: obj.optString("customer_email").takeIf { it.isNotEmpty() }
                        ?: "غير معروف"
                    val timeStr = java.text.SimpleDateFormat("hh:mm a", java.util.Locale("ar")).format(java.util.Date())
                    AppState.addNotification(DeviceNotification(
                        title = "طلب شحن جديد $orderNum",
                        message = "${obj.optDouble("amount_requested", 0.0)} جنيه • $customerInfo • $timeStr",
                        type = NotificationType.ORDER_NEW,
                        referenceId = requestId
                    ))
                }
            }
            if (tasks.isNotEmpty()) onPendingTasks(tasks)
        } catch (e: Exception) {
            android.util.Log.e("HeartbeatManager", "Failed to parse heartbeat response: ${e.message}")
        }
    }

    /** Handle a server→android command received in heartbeat */
    private fun handleServerCommand(cmd: org.json.JSONObject) {
        val commandId = cmd.optString("command_id")
        val commandType = cmd.optString("command_type", "test_ping")
        android.util.Log.d("HeartbeatManager", "Server command received: $commandType ($commandId)")

        AppState.addNotification(DeviceNotification(
            title = "أمر من الأدمن",
            message = "نوع: $commandType",
            type = NotificationType.TEST_RECEIVED,
            referenceId = commandId
        ))

        // ACK back to server
        val sentAt = cmd.optString("sent_at")
        val ackPayload = mapOf(
            "action" to "command_ack",
            "device_id" to deviceId,
            "command_id" to commandId,
            "sent_at" to sentAt,
            "response_data" to mapOf(
                "command_type" to commandType,
                "device_model" to (Build.MODEL ?: "Unknown"),
                "app_version" to BuildConfig.VERSION_NAME
            )
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, ackPayload) { success, _, _ ->
            if (success) {
                AppState.addNotification(DeviceNotification(
                    title = "تم الرد على الأدمن ✓",
                    message = "اختبار الاتصال ناجح",
                    type = NotificationType.TEST_SUCCESS,
                    referenceId = commandId
                ))
            }
        }
    }
}
