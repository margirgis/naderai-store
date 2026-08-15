package com.naderai.smsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * ForegroundService يُبقي التطبيق حياً في الخلفية ويرسل Heartbeat دورياً.
 * يتم تشغيله عند فتح التطبيق ويستمر حتى يُوقفه المستخدم يدوياً.
 */
class SmsMonitorService : Service() {

    private var heartbeatManager: HeartbeatManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "naderai_sms_monitor"
        private const val ACTION_STOP = "STOP_SERVICE"

        @JvmStatic
        var isRunning = false

        @JvmStatic
        private var serviceInstance: SmsMonitorService? = null

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
    } else {
        context.startService(intent)
}
}

        /** استدعاء عند استلام رسالة جديدة لفحص الطلبات المعلقة مرة واحدة */
        @JvmStatic
        fun onNewSmsReceived(context: Context) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
            val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl) ?: return
            val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: return
            val pending = AppState.pendingTasks.value ?: return
            if (pending.isEmpty()) return

            TaskScanner.taskResultCallback = { task, result ->
                AppState.onTaskResult(task, result)
    }

            pending.forEach { task ->
                // تحقق من الحالة النهائية في AppState قبل أي فحص
                val existingStatus = AppState.getOrders()
                    .firstOrNull { it.requestId == task.requestId }?.status
        if (existingStatus != null && existingStatus in setOf(
                OrderStatus.CONFIRMED, OrderStatus.NOT_FOUND,
                OrderStatus.AMOUNT_MISMATCH, OrderStatus.FAILED, OrderStatus.DUPLICATE)) {
    android.util.Log.d("SmsMonitorService",
        "onNewSms: skipping terminal order ${task.requestId} ($existingStatus)")
return@forEach
}
if (TaskResultCache.get(context, task.taskId) != null) return@forEach
processTask(context, task, webhookUrl, secret)
}
}

        private fun processTask(context: Context, task: TaskScanner.Task, webhookUrl: String, secret: String) {
            val cached = TaskResultCache.get(context, task.taskId)
            if (cached != null) {
                applyCachedStatus(task.requestId, cached)
                resendCachedResult(context, task, cached, webhookUrl, secret)
                return
            }

            // ── نافذة انتهاء الصلاحية: رفض فوري إذا انتهى الطلب ─────────
            if (!task.orderExpiresAt.isNullOrEmpty()) {
                try {
                    val expiresMs = java.time.Instant.parse(task.orderExpiresAt).toEpochMilli()
                    if (System.currentTimeMillis() > expiresMs) {
                        android.util.Log.w("SmsMonitorService",
                            "Task ${task.taskId}: order expired at ${task.orderExpiresAt} — rejecting immediately")
                        val body = mutableMapOf<String, Any>(
                            "action"         to "task_result",
                            "device_id"      to HeartbeatManager.getDeviceId(context),
                            "task_id"        to task.taskId,
                            "idempotency_key" to "${task.taskId}-${task.requestId}",
                            "status"         to "failure",
                            "failure_reason" to "انتهت صلاحية الطلب قبل وصول رسالة SMS"
                        )
                        WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { _, _, _ -> }
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SmsMonitorService", "Failed to parse orderExpiresAt: ${task.orderExpiresAt}", e)
                }
            }

            // تأكيد بداية الفحص في الـ UI
            AppState.updateOrderScanProgress(task.requestId, 1, TaskScanner.MAX_SCAN_ATTEMPTS, 0)
            TaskScanner.scanAndReport(context, task, webhookUrl, secret) { result, success ->
                TaskResultCache.put(context, task.taskId, result)
                if (!success) {
                    TaskResultCache.incrementRetry(context, task.taskId)
                }
            }
        }

        private fun applyCachedStatus(requestId: String, cached: TaskResultCache.CachedResult) {
            val status = when (cached.status) {
                "success" -> OrderStatus.CONFIRMED
                "amount_mismatch" -> OrderStatus.AMOUNT_MISMATCH
                "not_found" -> OrderStatus.NOT_FOUND
                "failure" -> OrderStatus.FAILED
                else -> OrderStatus.PENDING
    }
    if (status != OrderStatus.PENDING) AppState.updateOrderStatus(requestId, status)
}

        @JvmStatic
        fun forceScanTask(context: Context, task: TaskScanner.Task) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
            val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl) ?: return
            val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: return
            TaskResultCache.remove(context, task.taskId)
            AppState.updateOrderStatus(task.requestId, OrderStatus.PENDING)
            processTask(context, task, webhookUrl, secret)
        }

        @JvmStatic
        fun handlePendingTasks(context: Context, tasks: List<TaskScanner.Task>, webhookUrl: String, secret: String) {
            if (tasks.isEmpty()) {
                AppState.pendingTasks.postValue(emptyList())
                return
            }
            android.util.Log.d("SmsMonitorService", "Received ${tasks.size} pending tasks")
            AppState.pendingTasks.postValue(tasks)

            TaskScanner.taskResultCallback = { task, result ->
                AppState.onTaskResult(task, result)
            }

            tasks.forEach { task ->
                // حماية مزدوجة: تحقق من الحالة النهائية في AppState
                val existingStatus = AppState.getOrders()
                    .firstOrNull { it.requestId == task.requestId }?.status
                if (existingStatus != null && existingStatus in setOf(
                        OrderStatus.CONFIRMED, OrderStatus.NOT_FOUND,
                        OrderStatus.AMOUNT_MISMATCH, OrderStatus.FAILED, OrderStatus.DUPLICATE)) {
                    android.util.Log.d("SmsMonitorService",
                        "handlePendingTasks: skipping terminal order ${task.requestId} ($existingStatus)")
                    return@forEach
                }
                val cached = TaskResultCache.get(context, task.taskId)
                if (cached != null) {
                    android.util.Log.d("SmsMonitorService", "Task ${task.taskId} already cached, skipping re-scan")
                    applyCachedStatus(task.requestId, cached)
                    resendCachedResult(context, task, cached, webhookUrl, secret)
                    return@forEach
                }
                processTask(context, task, webhookUrl, secret)
            }
            serviceInstance?.updateNotification("جاري فحص ${tasks.size} طلب...")
        }

        private fun resendCachedResult(context: Context, task: TaskScanner.Task, cached: TaskResultCache.CachedResult, webhookUrl: String, secret: String) {
            if (!TaskResultCache.shouldRetry(context, task.taskId)) {
                android.util.Log.d("SmsMonitorService", "Task ${task.taskId} reached max retries, skipping")
                return
            }
            val resultData = cached.resultData?.let {
                try {
                    val obj = org.json.JSONObject(it)
                    mapOf(
                        "sender_phone"     to obj.optString("sender_phone"),
                        "sender_name"      to obj.optString("sender_name"),
                        "amount"           to obj.optDouble("amount", 0.0),
                        "transaction_id"   to obj.optString("transaction_id"),
                        "transaction_time" to obj.optString("transaction_time"),
                        "receiver_wallet"  to obj.optString("receiver_wallet"),
                        "sms_body"         to obj.optString("sms_body"),
                        "scanned_at"       to obj.optString("scanned_at")
                    )
                } catch (e: Exception) { null }
            }
            val body = mutableMapOf<String, Any>(
                "action"          to "task_result",
                "device_id"       to HeartbeatManager.getDeviceId(context),
                "task_id"         to task.taskId,
                "idempotency_key" to "${task.taskId}-${task.requestId}",
                "status"          to cached.status
            )
            if (resultData != null)                   body["result_data"]       = resultData
            if (!cached.failureReason.isNullOrEmpty()) body["failure_reason"]   = cached.failureReason
            if (!task.paymentOrderId.isNullOrEmpty())  body["payment_order_id"] = task.paymentOrderId
            if (!task.orderExpiresAt.isNullOrEmpty())  body["order_expires_at"] = task.orderExpiresAt

            WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { success, _, _ ->
                if (success) {
                    TaskResultCache.remove(context, task.taskId)
                } else {
                    TaskResultCache.incrementRetry(context, task.taskId)
                }
            }
        }
}

    override fun onCreate() {
        super.onCreate()
        try {
            isRunning = true
            serviceInstance = this
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("جاري الاتصال..."))
            acquireWakeLock()
} catch (e: Exception) {
    android.util.Log.e("SmsMonitorService", "Failed to start service: ${e.message}", e)
    AppState.lastError.postValue("فشل تشغيل خدمة SMS: ${e.message}")
    stopSelf()
}
}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
    }
}

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
        val secret = prefs.getString(MainActivity.KEY_SECRET, null)

        if (!webhookUrl.isNullOrEmpty() && !secret.isNullOrEmpty()) {
            heartbeatManager?.stop()
            heartbeatManager = HeartbeatManager(
                this,
                webhookUrl,
                secret,
                onStatusChange = { connected, message ->
                    updateNotification(if (connected) "متصل ✓" else "غير متصل: $message")
        },
        onPendingTasks = { tasks ->
            handlePendingTasks(this, tasks, webhookUrl, secret)
}
)
heartbeatManager?.start()
} else {
    updateNotification("في انتظار الإعدادات...")
}

        return START_STICKY
}

    override fun onDestroy() {
        isRunning = false
        serviceInstance = null
        heartbeatManager?.stop()
        heartbeatManager = null
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NaderAI::SmsMonitorWakeLock"
        ).also { it.acquire() }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SmsMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nader AI SMS Reader")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "إيقاف", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nader AI SMS Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يقوم بمراقبة طلبات الشحن وإرسال Heartbeat"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}