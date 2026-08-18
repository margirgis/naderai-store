package com.naderai.appstore.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.naderai.appstore.BuildConfig

/**
 * يزامن المهام مع السيرفر بشكل دوري.
 * يرسل heartbeat فورًا عند التشغيل ثم كل 15 ثانية مؤقتًا.
 */
class HeartbeatManager(
    private val context: Context,
    private val webhookUrl: String,
    private val secret: String,
    private val onStatusChange: (connected: Boolean, message: String) -> Unit,
    private val onPendingTasks: (List<TaskScanner.Task>) -> Unit = {}
) {
    private val TAG = "HeartbeatManager"
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId get() = DeviceInfo.getDeviceId(context)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        registerDevice()
        sendHeartbeat()
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        Log.d(TAG, "HeartbeatManager started — interval=${HEARTBEAT_INTERVAL_MS}ms")
    }

    fun refreshNow() {
        handler.post { sendHeartbeat() }
    }

    fun stop() {
        handler.removeCallbacks(heartbeatRunnable)
        Log.d(TAG, "HeartbeatManager stopped")
    }

    fun registerDevice() {
        val payload = mapOf(
            "action" to "device_register",
            "device_id" to deviceId,
            "device_model" to DeviceInfo.getModel(),
            "device_name" to DeviceInfo.getDeviceName(),
            "android_version" to DeviceInfo.getAndroidVersion(),
            "app_version" to BuildConfig.VERSION_NAME,
            "phone_number" to "",
            "capabilities" to mapOf(
                "sms_read" to true,
                "sms_receive" to true,
                "realtime_scan" to true,
                "webview_app" to true
            )
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { ok, msg, body ->
            if (ok) {
                Log.d(TAG, "Device registered OK: $body")
                onStatusChange(true, "مسجل ✓")
            } else {
                Log.w(TAG, "Device register failed: $msg")
                onStatusChange(false, msg)
            }
        }
    }

    private fun sendHeartbeat() {
        val payload = buildMap<String, Any> {
            put("action", "heartbeat")
            put("device_id", deviceId)
            put("app_version", BuildConfig.VERSION_NAME)
            put("android_version", DeviceInfo.getAndroidVersion())
            put("model", DeviceInfo.getModel())
            put("ts", System.currentTimeMillis())
        }
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { ok, msg, body ->
            onStatusChange(ok, if (ok) "متصل" else "انقطع الاتصال: $msg")
            if (ok) parsePendingTasks(body)
        }
    }

    private fun parsePendingTasks(responseBody: String) {
        try {
            val json = org.json.JSONObject(responseBody)
            val arr = json.optJSONArray("pending_tasks") ?: json.optJSONArray("tasks")
            if (arr == null) {
                Log.w(TAG, "Heartbeat response has no pending_tasks/tasks array")
                return
            }

            val tasks = mutableListOf<TaskScanner.Task>()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tasks.add(
                    TaskScanner.Task(
                        taskId = t.optString("id"),
                        requestId = t.optString("request_id"),
                        amountRequested = t.optDouble("amount_requested", 0.0),
                        senderPhoneRequested = t.optString("sender_phone_requested")
                            .takeIf { it.isNotEmpty() },
                        fingerprintAmount = t.optDouble("fingerprint_amount", Double.NaN)
                            .takeUnless { it.isNaN() },
                        senderNameRequested = t.optString("sender_name_requested")
                            .takeIf { it.isNotEmpty() },
                        receiverWalletRequested = t.optString("receiver_wallet_requested")
                            .takeIf { it.isNotEmpty() },
                        transactionIdExpected = t.optString("transaction_id_expected")
                            .takeIf { it.isNotEmpty() }
                    )
                )
            }

            Log.d(TAG, "Received ${tasks.size} pending tasks")
            onPendingTasks(tasks)
        } catch (e: Exception) {
            Log.e(TAG, "parsePendingTasks error: ${e.message}")
        }
    }
}