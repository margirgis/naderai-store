package com.naderai.appstore.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.naderai.appstore.BuildConfig

/**
 * يزامن المهام مع السيرفر بشكل دوري.
 * يرسل heartbeat فورًا عند التشغيل ثم كل 15 ثانية.
 * عند الفشل: exponential backoff (2s → 30s max)، ثم يُعيد الـ heartbeat الطبيعي.
 * يستمع لعودة الشبكة ويُعيد الاتصال فورًا.
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

    // ── حالة الاتصال الداخلية ────────────────────────────────────────
    @Volatile private var consecutiveFailures = 0
    @Volatile private var isRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val BACKOFF_BASE_MS       = 2_000L
        private const val BACKOFF_MAX_MS        = 30_000L
        private const val MAX_BACKOFF_STEPS     = 8
    }

    // ── Heartbeat دوري (15 ث) ──────────────────────────────────────
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        isRunning = true
        consecutiveFailures = 0
        registerDevice()
        sendHeartbeat()
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        registerNetworkCallback()
        Log.d(TAG, "HeartbeatManager started — interval=${HEARTBEAT_INTERVAL_MS}ms")
    }

    fun refreshNow() {
        handler.post { sendHeartbeat() }
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        Log.d(TAG, "HeartbeatManager stopped")
    }

    // ── تسجيل الجهاز ──────────────────────────────────────────────
    fun registerDevice() {
        val payload = mapOf(
            "action"       to "device_register",
            "device_id"    to deviceId,
            "device_model" to DeviceInfo.getModel(),
            "device_name"  to DeviceInfo.getDeviceName(),
            "android_version" to DeviceInfo.getAndroidVersion(),
            "app_version"  to BuildConfig.VERSION_NAME,
            "phone_number" to "",
            "capabilities" to mapOf(
                "sms_read"       to true,
                "sms_receive"    to true,
                "realtime_scan"  to true,
                "webview_app"    to true
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

    // ── إرسال Heartbeat مع backoff عند الفشل ──────────────────────
    private fun sendHeartbeat() {
        val payload = buildMap<String, Any> {
            put("action",          "heartbeat")
            put("device_id",       deviceId)
            put("app_version",     BuildConfig.VERSION_NAME)
            put("android_version", DeviceInfo.getAndroidVersion())
            put("model",           DeviceInfo.getModel())
            put("ts",              System.currentTimeMillis())
        }
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { ok, msg, body ->
            onStatusChange(ok, if (ok) "متصل" else "انقطع الاتصال: $msg")
            if (ok) {
                consecutiveFailures = 0
                parsePendingTasks(body)
            } else {
                handleFailure()
            }
        }
    }

    /**
     * عند الفشل: تجاهل الـ timer الدوري مؤقتاً وأعد المحاولة بعد backoff أسي.
     * بعد MAX_BACKOFF_STEPS نتوقف حتى تعود الشبكة (NetworkCallback يعيد الاتصال).
     */
    private fun handleFailure() {
        consecutiveFailures++
        if (consecutiveFailures > MAX_BACKOFF_STEPS) {
            // توقف كامل — ننتظر NetworkCallback
            handler.removeCallbacks(heartbeatRunnable)
            Log.w(TAG, "Max retries reached ($MAX_BACKOFF_STEPS). Waiting for network.")
            return
        }
        val delay = minOf(BACKOFF_BASE_MS * (1L shl (consecutiveFailures - 1)), BACKOFF_MAX_MS)
        Log.d(TAG, "Heartbeat failed ($consecutiveFailures). Retry in ${delay}ms")
        // أوقف الـ periodic timer وأعد تشغيله بعد الـ backoff
        handler.removeCallbacks(heartbeatRunnable)
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            sendHeartbeat()
            // أعد تشغيل الدورة الطبيعية بعد نجاح أو فشل هذه المحاولة
            handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        }, delay)
    }

    // ── استماع لعودة الشبكة ──────────────────────────────────────
    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available — reconnecting immediately")
                    handler.post {
                        if (!isRunning) return@post
                        consecutiveFailures = 0
                        handler.removeCallbacks(heartbeatRunnable)
                        sendHeartbeat()
                        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
                    }
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
        }
    }

    // ── تحليل المهام من الـ response ─────────────────────────────
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
                        taskId                = t.optString("id"),
                        requestId             = t.optString("request_id"),
                        amountRequested       = t.optDouble("amount_requested", 0.0),
                        senderPhoneRequested  = t.optString("sender_phone_requested").takeIf { it.isNotEmpty() },
                        fingerprintAmount     = t.optDouble("fingerprint_amount", Double.NaN).takeUnless { it.isNaN() },
                        senderNameRequested   = t.optString("sender_name_requested").takeIf { it.isNotEmpty() },
                        receiverWalletRequested = t.optString("receiver_wallet_requested").takeIf { it.isNotEmpty() },
                        transactionIdExpected = t.optString("transaction_id_expected").takeIf { it.isNotEmpty() }
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