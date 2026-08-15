package com.naderai.smsreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build

class HeartbeatManager(
    private val context: Context,
    private val webhookUrl: String,
    private val secret: String,
    private val onStatusChange: (Boolean, String) -> Unit,
    private val onPendingTasks: (List<TaskScanner.Task>) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId: String
        get() = Companion.getDeviceId(context)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_DEVICE_ID = "device_id"

        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: (System.currentTimeMillis().toString())
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
            "app_version" to "1.0.3",
            "phone_number" to ""
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { success, message, responseBody ->
            onStatusChange(success, if (success) "متصل" else "غير متصل: $message")
            if (success) parseHeartbeatResponse(responseBody)
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
                    senderPhoneRequested = obj.optString("sender_phone_requested", null),
                    senderNameRequested = obj.optString("sender_name_requested", null),
                    fingerprintAmount = if (obj.has("fingerprint_amount")) obj.optDouble("fingerprint_amount", 0.0) else null,
                    creditsAmount = if (obj.has("credits_amount")) obj.optDouble("credits_amount", 0.0) else null
                ))
            }
            onPendingTasks(tasks)
        } catch (e: Exception) {
            android.util.Log.d("HeartbeatManager", "Failed to parse pending tasks: ${e.message}")
        }
    }
}
