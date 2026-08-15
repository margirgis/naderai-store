package com.naderai.smsreader

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

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
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
        sendHeartbeat()
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    private fun sendHeartbeat() {
        val payload = mapOf(
            "action" to "heartbeat",
            "device_id" to deviceId,
            "device_model" to (Build.MODEL ?: "Unknown"),
            "device_name" to (Build.DEVICE ?: "Unknown"),
            "android_version" to (Build.VERSION.RELEASE ?: "Unknown"),
            "app_version" to "1.0.4",
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
                // On reconnect, drain any offline retry queue
                if (justReconnected) {
                    RetryQueue.drainOnReconnect(context, webhookUrl, secret)
                }
            }
        }
    }

    fun parseHeartbeatResponse(responseBody: String) {
        try {
            val json = org.json.JSONObject(responseBody)
            if (!json.has("pending_tasks")) return
            val arr = json.getJSONArray("pending_tasks")
            val tasks = mutableListOf<TaskScanner.Task>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tasks.add(TaskScanner.Task(
                    taskId = obj.getString("task_id"),
                    requestId = obj.getString("request_id"),
                    amountRequested = obj.optDouble("amount_requested", 0.0),
                    senderPhoneRequested = obj.optString("sender_phone_requested").takeIf { it.isNotEmpty() },
                    senderNameRequested = obj.optString("sender_name_requested").takeIf { it.isNotEmpty() },
                    fingerprintAmount = if (obj.has("fingerprint_amount")) obj.optDouble("fingerprint_amount") else null,
                    creditsAmount = if (obj.has("credits_amount")) obj.optDouble("credits_amount") else null
                ))
                // Add order to AppState for UI tracking
                AppState.addOrUpdateOrder(OrderItem(
                    requestId = obj.getString("request_id"),
                    orderLabel = "طلب شحن",
                    expectedAmount = obj.optDouble("amount_requested", 0.0),
                    status = OrderStatus.SCANNING,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ))
            }
            onPendingTasks(tasks)
        } catch (e: Exception) {
            android.util.Log.d("HeartbeatManager", "Failed to parse pending tasks: ${e.message}")
        }
    }
}
