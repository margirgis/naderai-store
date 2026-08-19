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
    private var orderSyncManager: OrderSyncManager? = null
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
            NetworkMonitor.start(context.applicationContext)
        }

        /**
         * طلب مزامنة فورية من خلال خدمة الـ Heartbeat.
         */
        @JvmStatic
        fun forceSync(context: Context) {
            start(context)
            serviceInstance?.heartbeatManager?.forceSync()
            android.util.Log.d("SmsMonitorService", "forceSync requested")
        }

        /** استدعاء عند استلام رسالة جديدة لفحص الطلبات المعلقة مرة واحدة */
        @JvmStatic
        fun onNewSmsReceived(context: Context) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
            val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
            val secret = prefs.getString(MainActivity.KEY_SECRET, null)
            val adminLoggedIn = AdminSession.isLoggedIn(context)

            if (!adminLoggedIn && (webhookUrl.isNullOrEmpty() || secret.isNullOrEmpty())) {
                android.util.Log.d("SmsMonitorService", "onNewSmsReceived: no webhook config or admin session")
                return
            }
            val pending = AppState.pendingTasks.value ?: return
            if (pending.isEmpty()) return

            TaskScanner.taskResultCallback = { task, result ->
                AppState.onTaskResult(task, result)
    }

            pending.forEach { task ->
                // تحقق من الحالة النهائية في AppState قبل أي فحص
                val existingStatus = AppState.getOrders()
                    .firstOrNull { it.requestId == task.requestId }?.status
                if (existingStatus?.isTerminal() == true) {
                    android.util.Log.d("SmsMonitorService",
                        "onNewSms: skipping terminal order ${task.requestId} (${existingStatus.name})")
                    return@forEach
                }
                // لا نعيد فتح الطلبات الفاشلة/المنتهية تلقائياً — يدوي فقط
                if (existingStatus != null && existingStatus != OrderStatus.PENDING && existingStatus != OrderStatus.SCANNING) {
                    return@forEach
                }
                if (TaskResultCache.get(context, task.taskId) != null) return@forEach
                processTask(context, task, webhookUrl ?: "", secret ?: "")
            }

}

        private fun processTask(context: Context, task: TaskScanner.Task, webhookUrl: String, secret: String) {
            val cached = TaskResultCache.get(context, task.taskId)
            if (cached != null) {
                applyCachedStatus(task.requestId, cached)
                resendCachedResult(context, task, cached, webhookUrl, secret)
                return
            }

            // ── نافذة انتهاء الصلاحية ─────────
            // لو الطلب انتهى فوق شروط مسموح بيها (مثلاً 24 ساعة)، نرفض فوري.
            // لكن الآن نتيح فتح الطلب للمراجعة في الاتصال الأولى، لذا نمنح الفحص الحاقزي في نفسه.
            if (!task.orderExpiresAt.isNullOrEmpty()) {
                try {
                    val expiresMs = java.time.Instant.parse(task.orderExpiresAt).toEpochMilli()
                    val deadlineMs = expiresMs + 24 * 60 * 60 * 1000L // 24 ساعة فوق انتهاء الصلاحية
                    if (System.currentTimeMillis() > deadlineMs) {
                        android.util.Log.w("SmsMonitorService",
                            "Task ${task.taskId}: order expired long ago (${task.orderExpiresAt}) — rejecting immediately")
                        TaskScanner.sendTaskResult(
                            context = context,
                            task = task,
                            result = TaskScanner.ScanResult.Failure("انتهت صلاحية الطلب منذ فترة طويلة"),
                            webhookUrl = webhookUrl,
                            secret = secret,
                            onSent = { _ -> }
                        )
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SmsMonitorService", "Failed to parse orderExpiresAt: ${task.orderExpiresAt}", e)
                }
            }

            // تأكيد بداية الفحص في الـ UI
            AppState.updateOrderScanProgress(task.requestId, 1, TaskScanner.MAX_SCAN_ATTEMPTS, 0)
            OrderEventLogger.scanStarted(task.requestId, task.orderNumber, task.taskId)
            TaskScanner.scanAndReport(context, task, webhookUrl, secret) { result, success ->
                TaskResultCache.put(context, task.taskId, result)
                if (!success) {
                    TaskResultCache.incrementRetry(context, task.taskId)
                }
                // ── P0 FIX: قراءة رد السيرفر لتحديد الحالة النهائية ────────────
                // sendAdminTaskResult يُعيد responseBody — نقرأه في TaskScanner.taskResultCallback
                // لكن scanAndReport يستدعي sendTaskResult داخلياً مع onSent فقط.
                // الحل: نستعمل TaskScanner.taskResultCallback لتحديث الحالة المحلية بعد رد السيرفر.
            }
        }

        private fun applyCachedStatus(requestId: String, cached: TaskResultCache.CachedResult) {
            // حالة الكاش تعكس ما أرسله الجهاز — ليس قرار السيرفر.
            // نضع MANUAL_REVIEW لو success (السيرفر لم يرد بعد من الكاش)
            // أو الحالات الأخرى مباشرة.
            val status = when (cached.status) {
                "success"        -> OrderStatus.MANUAL_REVIEW
                "amount_mismatch"-> OrderStatus.AMOUNT_MISMATCH
                "not_found"      -> OrderStatus.NOT_FOUND
                "failure"        -> OrderStatus.FAILED
                else             -> OrderStatus.PENDING
            }
            if (status != OrderStatus.PENDING) {
                AppState.updateOrderStatus(requestId, status)
            }
        }

        @JvmStatic
        fun forceScanTask(context: Context, task: TaskScanner.Task) {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
            val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
            val secret = prefs.getString(MainActivity.KEY_SECRET, null)
            if (!AdminSession.isLoggedIn(context) && (webhookUrl.isNullOrEmpty() || secret.isNullOrEmpty())) {
                android.util.Log.w("SmsMonitorService", "forceScanTask: no webhook or admin session")
                return
            }
            // السماح بإعادة الفحص اليدوي: نمسح الكاش ونلغي القفل السابق
            TaskResultCache.remove(context, task.taskId)
            TaskScanner.clearScanLock(task.taskId)
            AppState.updateOrderStatus(task.requestId, OrderStatus.PENDING)
            AppState.updateOrderScanProgress(task.requestId, 0, TaskScanner.MAX_SCAN_ATTEMPTS, 0)
            processTask(context, task, webhookUrl ?: "", secret ?: "")
        }

        @JvmStatic
        fun handlePendingTasks(context: Context, tasks: List<TaskScanner.Task>, webhookUrl: String, secret: String) {
            if (tasks.isEmpty()) {
                android.util.Log.d("SmsMonitorService", "handlePendingTasks called with empty list")
                AppState.pendingTasks.postValue(emptyList())
                return
            }
            android.util.Log.d("SmsMonitorService", "Received ${tasks.size} pending tasks, starting scan")
            AppState.pendingTasks.postValue(tasks)

            TaskScanner.taskResultCallback = { task, result ->
                AppState.onTaskResult(task, result)
            }

            tasks.forEach { task ->
                // حماية مزدوجة: تحقق من الحالة النهائية في AppState
                val existingStatus = AppState.getOrders()
                    .firstOrNull { it.requestId == task.requestId }?.status
                if (existingStatus?.isTerminal() == true) {
                    android.util.Log.d("SmsMonitorService",
                        "handlePendingTasks: skipping terminal order ${task.requestId} (${existingStatus.name})")
                    return@forEach
                }
                if (existingStatus != null && existingStatus != OrderStatus.PENDING && existingStatus != OrderStatus.SCANNING) {
                    // لا نعيد فتح الطلبات غير المعلقة تلقائياً
                    return@forEach
                }

                // لو task_id مش موجود، الطلب ده مش له مهمة فحص — تجاهله
                if (task.taskId.isBlank()) {
                    android.util.Log.w("SmsMonitorService",
                        "handlePendingTasks: task for request ${task.requestId} has no taskId — skipping")
                    return@forEach
                }
                val cached = TaskResultCache.get(context, task.taskId)
                if (cached != null) {
                    android.util.Log.d("SmsMonitorService", "Task ${task.taskId} already cached, skipping re-scan")
                    applyCachedStatus(task.requestId, cached)
                    resendCachedResult(context, task, cached, webhookUrl, secret)
                    return@forEach
                }
                if (TaskScanner.isScanning(task.taskId)) {
                    android.util.Log.d("SmsMonitorService", "Task ${task.taskId} is already scanning")
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
            TaskScanner.resendCachedResult(context, task, cached, webhookUrl, secret)
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
} else if (AdminSession.isLoggedIn(this)) {
    updateNotification("متصل كأدمن ✓")
    // مزامنة دورية كل 10 ثوانٍ للأدمن لتحديث الحالات مباشرة
    orderSyncManager?.stop()
    orderSyncManager = OrderSyncManager(this, SupabaseConfig.getAdminUrl(webhookUrl ?: "") ?: "") { success, msg ->
        if (success) updateNotification("تمت المزامنة ✓")
    }
    orderSyncManager?.start()
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
        orderSyncManager?.stop()
        orderSyncManager = null
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