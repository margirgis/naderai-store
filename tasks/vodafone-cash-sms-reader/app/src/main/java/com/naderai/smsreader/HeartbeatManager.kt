package com.naderai.smsreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Build

class HeartbeatManager(
    private val context: Context,
    val webhookUrl: String,
    val secret: String,
    private val onStatusChange: (Boolean, String) -> Unit,
    private val onPendingTasks: (List<TaskScanner.Task>) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId: String get() = Companion.getDeviceId(context)
    private var wasConnected = false
    private var registered = false

    // ── Exponential backoff state ─────────────────────────────────
    @Volatile private var consecutiveFailures = 0
    @Volatile private var isRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        // 10s عند وجود مهام قيد الفحص، 20s في حالة الخمول (بدل 60s لتسريع استلام الطلبات الجديدة)
        private const val HEARTBEAT_INTERVAL_ACTIVE_MS = 10_000L
        private const val HEARTBEAT_INTERVAL_IDLE_MS = 20_000L
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
        // Exponential backoff constants
        private const val BACKOFF_BASE_MS  = 2_000L
        private const val BACKOFF_MAX_MS   = 30_000L
        private const val MAX_BACKOFF_STEPS = 8
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
            // تحديد الفاصل حسب وجود مهام قيد الفحص — لتحسين البطارية
            val hasPending = (AppState.pendingTasks.value?.size ?: 0) > 0
            val interval = if (hasPending) HEARTBEAT_INTERVAL_ACTIVE_MS else HEARTBEAT_INTERVAL_IDLE_MS
            handler.postDelayed(this, interval)
        }
    }

    fun start() {
        isRunning = true
        consecutiveFailures = 0
        // Always register first, then start heartbeat loop
        registerDevice()
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_ACTIVE_MS)
        registerNetworkCallback()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
    }

    /** مزامنة فورية — تُستخدم عند إعادة الاتصال أو بدء التطبيق */
    fun forceSync() {
        handler.removeCallbacks(heartbeatRunnable)
        consecutiveFailures = 0
        registerDevice()
        // بعد التسجيل سيرسل Heartbeat أول مباشرةً
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_ACTIVE_MS)
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
                consecutiveFailures = 0
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
                scheduleBackoffRetry()
            }
        }
    }

    // ── Exponential backoff — على فشل Heartbeat ──────────────────
    private fun scheduleBackoffRetry() {
        consecutiveFailures++
        if (consecutiveFailures > MAX_BACKOFF_STEPS) {
            // أوقف الـ periodic timer — ننتظر NetworkCallback
            handler.removeCallbacks(heartbeatRunnable)
            android.util.Log.w("HeartbeatManager", "Max retries ($MAX_BACKOFF_STEPS). Waiting for network.")
            return
        }
        val delay = minOf(BACKOFF_BASE_MS * (1L shl (consecutiveFailures - 1)), BACKOFF_MAX_MS)
        android.util.Log.d("HeartbeatManager", "Heartbeat failed ($consecutiveFailures). Retry in ${delay}ms")
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            sendHeartbeat()
            val hasPending = (AppState.pendingTasks.value?.size ?: 0) > 0
            val interval = if (hasPending) HEARTBEAT_INTERVAL_ACTIVE_MS else HEARTBEAT_INTERVAL_IDLE_MS
            handler.postDelayed(heartbeatRunnable, interval)
        }, delay)
    }

    // ── NetworkCallback — يُعيد الاتصال فور عودة الشبكة ─────────
    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("HeartbeatManager", "Network available — reconnecting")
                    handler.post {
                        if (!isRunning) return@post
                        consecutiveFailures = 0
                        handler.removeCallbacks(heartbeatRunnable)
                        sendHeartbeat()
                        val hasPending = (AppState.pendingTasks.value?.size ?: 0) > 0
                        val interval = if (hasPending) HEARTBEAT_INTERVAL_ACTIVE_MS else HEARTBEAT_INTERVAL_IDLE_MS
                        handler.postDelayed(heartbeatRunnable, interval)
                    }
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            android.util.Log.w("HeartbeatManager", "registerNetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            android.util.Log.w("HeartbeatManager", "unregisterNetworkCallback: ${e.message}")
        }
    }

    fun parseHeartbeatResponse(responseBody: String) {
        try {
            val json = org.json.JSONObject(responseBody)

            // ── تحقق من التحديث الإجباري ───────────────────────────────────────
            val minVersionCode = json.optInt("min_version_code", 0)
            if (minVersionCode > 0 && BuildConfig.VERSION_CODE < minVersionCode) {
                android.util.Log.w("HeartbeatManager",
                    "Force update required: current=${BuildConfig.VERSION_CODE} required=$minVersionCode")
                AppState.forceUpdateRequired.postValue(true)
                // لا نستمر في معالجة المهام — الجهاز يجب أن يتحدث أولاً
                return
            }
            AppState.forceUpdateRequired.postValue(false)

            // معالجة الأوامر من السيرفر (server → android)
            if (json.has("commands")) {
                val cmds = json.getJSONArray("commands")
                for (i in 0 until cmds.length()) {
                    val cmd = cmds.getJSONObject(i)
                    handleServerCommand(cmd)
                }
            }

            // تسجيل عدد الطلبات التي تم توزيعها حديثاً على هذا الجهاز
            val newlyDispatched = json.optInt("newly_dispatched", 0)
            if (newlyDispatched > 0) {
                android.util.Log.d("HeartbeatManager", "ORDER_DISPATCHED: $newlyDispatched new task(s) to device=$deviceId")
            }
            val reassignedFromOffline = json.optInt("reassigned_from_offline", 0)
            if (reassignedFromOffline > 0) {
                android.util.Log.d("HeartbeatManager", "STALE_DEVICE_REASSIGNED: $reassignedFromOffline task(s) reset and dispatched to device=$deviceId")
                OrderEventLogger.staleDeviceReassigned(reassignedFromOffline, deviceId)
            }

            // قراءة المهام المعلقة
            val tasksArr = when {
                json.has("pending_tasks") -> json.getJSONArray("pending_tasks")
                json.has("tasks") -> json.getJSONArray("tasks")
                else -> {
                    android.util.Log.d("HeartbeatManager", "Heartbeat response has no tasks")
                    return
                }
            }
            android.util.Log.d("HeartbeatManager", "Received ${tasksArr.length()} task(s) in heartbeat")
            val tasks = mutableListOf<TaskScanner.Task>()
            val seenTaskIds = mutableSetOf<String>()
            for (i in 0 until tasksArr.length()) {
                val obj = tasksArr.getJSONObject(i)
                val requestId = obj.getString("request_id")
                val taskId = obj.getString("task_id")

                // ══════════════════════════════════════════════════════════════
                // حماية الحالات النهائية: لا نعيد فتح طلب منتهي أو فاشل.
                // ══════════════════════════════════════════════════════════════
                val existingOrder = AppState.getOrders().firstOrNull { it.requestId == requestId }
                if (existingOrder != null && existingOrder.status.isTerminal()) {
                    android.util.Log.d("HeartbeatManager",
                        "Skipping terminal order $requestId (status=${existingOrder.status.name})")
                    OrderEventLogger.terminalIgnored(requestId, existingOrder.orderNumber, existingOrder.status.name)
                    continue
                }
                // لا نعيد فتح الطلبات الفاشلة/غير المكتملة تلقائياً — يدوي فقط


                if (!seenTaskIds.add(taskId)) {
                    android.util.Log.w("HeartbeatManager", "Duplicate task_id in heartbeat: $taskId")
                    OrderEventLogger.duplicateIgnored(requestId, existingOrder?.orderNumber, taskId)
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
                    customerName = obj.optString("customer_name").takeIf { it.isNotEmpty() },
                    paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                    requestCreatedAt = obj.optString("request_created_at").takeIf { it.isNotEmpty() },
                    paymentOrderId = obj.optString("payment_order_id").takeIf { it.isNotEmpty() },
                    orderExpiresAt = obj.optString("order_expires_at").takeIf { it.isNotEmpty() }
                )
                tasks.add(task)

                // تحديث أو إضافة الطلب في AppState — addOrUpdateOrder يحمي الحالات النهائية
                val orderNumber = if (obj.has("order_number") && !obj.isNull("order_number")) obj.getLong("order_number") else null
                // استخدام request_created_at من السيرفر إذا توفر
                val requestCreatedAtMs: Long = run {
                    val raw = obj.optString("request_created_at").takeIf { it.isNotEmpty() }
                    if (raw != null) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            sdf.parse(raw.replace(Regex("\\+\\d{2}:?\\d{2}$"), "").replace(Regex("\\.\\d+$"), ""))?.time
                                ?: System.currentTimeMillis()
                        } catch (e: Exception) { System.currentTimeMillis() }
                    } else System.currentTimeMillis()
                }
                AppState.addOrUpdateOrder(OrderItem(
                    requestId = requestId,
                    orderLabel = "طلب شحن",
                    expectedAmount = obj.optDouble("amount_requested", 0.0),
                    status = OrderStatus.PENDING,
                    createdAt = requestCreatedAtMs,
                    updatedAt = System.currentTimeMillis(),
                    orderNumber = orderNumber,
                    creditsRequested = if (obj.has("credits_requested") && !obj.isNull("credits_requested")) obj.getInt("credits_requested") else null,
                    customerEmail = obj.optString("customer_email").takeIf { it.isNotEmpty() },
                    customerPhone = obj.optString("customer_phone").takeIf { it.isNotEmpty() },
                    customerName = obj.optString("customer_name").takeIf { it.isNotEmpty() },
                    requestCreatedAt = obj.optString("request_created_at").takeIf { it.isNotEmpty() },
                    senderPhoneRequested = obj.optString("sender_phone_requested").takeIf { it.isNotEmpty() },
                    senderNameRequested = obj.optString("sender_name_requested").takeIf { it.isNotEmpty() },
                    paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                    taskId = taskId,
                    paymentOrderId = obj.optString("payment_order_id").takeIf { it.isNotEmpty() },
                    orderExpiresAt = obj.optString("order_expires_at").takeIf { it.isNotEmpty() }
                ))

                OrderEventLogger.orderDelivered(requestId, orderNumber, deviceId, "PENDING")

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
