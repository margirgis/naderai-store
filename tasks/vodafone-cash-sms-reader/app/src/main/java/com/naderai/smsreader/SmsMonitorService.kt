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
                // السماح بإعادة الفحص لـ MANUAL_REVIEW و NOT_FOUND عند وصول SMS جديدة
                val rescanAllowed = existingStatus == null ||
                    existingStatus == OrderStatus.PENDING ||
                    existingStatus == OrderStatus.SCANNING ||
                    existingStatus == OrderStatus.MANUAL_REVIEW ||
                    existingStatus == OrderStatus.NOT_FOUND
                if (!rescanAllowed) {
                    return@forEach
                }
                if (TaskResultCache.get(context, task.taskId) != null) return@forEach
                processTask(context, task, webhookUrl ?: "", secret ?: "")
            }
        }

        // Fix #3: حماية task=null — نتحقق من taskId قبل أي processing
        private fun processTask(context: Context, task: TaskScanner.Task, webhookUrl: String, secret: String) {

        // ── Fix #3: TASK_ID_MISSING guard ─────────────────────────────────────────
        if (task.taskId.isBlank()) {
            android.util.Log.e("SmsMonitorService",
                "TASK_ID_MISSING | order=${task.requestId} orderNum=${task.orderNumber} — aborting, no taskId to poll")
            OrderDiagnosticsLog.log(
                OrderDiagnosticsLog.EventType.GENERIC_ERROR,
                task.orderNumber, task.requestId, null,
                details = "TASK_ID_MISSING: order=${task.requestId} لا يحتوي على task_id — لا يمكن بدء الفحص"
            )
            AppState.updateOrderStatus(task.requestId, OrderStatus.FAILED)
            return
        }

        val cached = TaskResultCache.get(context, task.taskId)
            if (cached != null) {
                OrderEventLogger.scanFromCache(task.requestId, task.orderNumber, task.taskId)
                applyCachedStatus(task.requestId, cached)
                resendCachedResult(context, task, cached, webhookUrl, secret)
                return
            }

            // Phase-1: تسجيل received_at في AppState عند بدء المعالجة الفعلية
            val receivedMs = System.currentTimeMillis()
            AppState.getOrders().firstOrNull { it.requestId == task.requestId }?.let { existing ->
                if (existing.receivedAt == null) {
                    AppState.addOrUpdateOrder(existing.copy(receivedAt = receivedMs))
                }
            }
            android.util.Log.d("SmsMonitorService",
                "TASK_RECEIVED | order=${task.requestId} task=${task.taskId} " +
                "queued=${task.queuedAt} dispatched=${task.dispatchedAt} received=${java.time.Instant.ofEpochMilli(receivedMs)}")

            // Fix #4: ORDER_EXPIRED مستقل تماماً عن SESSION_EXPIRED
            // نحدد سبب التجاهل بدقة: وقت الانتهاء + وقت الاستلام + الفارق
            if (!task.orderExpiresAt.isNullOrEmpty()) {
                try {
                    val expiresMs = java.time.Instant.parse(task.orderExpiresAt).toEpochMilli()
                    val nowMs = System.currentTimeMillis()
                    if (nowMs > expiresMs) {
                        val overdueSec = (nowMs - expiresMs) / 1000
                        android.util.Log.w("SmsMonitorService",
                            "ORDER_EXPIRED | order=${task.requestId} task=${task.taskId} " +
                            "expires=${task.orderExpiresAt} overdue_sec=$overdueSec reason=order_expiry_not_session")
                        OrderDiagnosticsLog.log(
                            OrderDiagnosticsLog.EventType.ORDER_SKIPPED,
                            task.orderNumber, task.requestId, task.taskId,
                            details = "ORDER_EXPIRED: expires=${task.orderExpiresAt} overdue_sec=$overdueSec — ليس SESSION_EXPIRED"
                        )
                        // تحديث حالة الطلب إلى EXPIRED لا FAILED
                        AppState.updateOrderStatus(task.requestId, OrderStatus.EXPIRED)
                        TaskScanner.sendTaskResult(
                            context = context,
                            task = task,
                            result = TaskScanner.ScanResult.Failure("ORDER_EXPIRED: انتهت صلاحية الطلب منذ ${overdueSec}s"),
                            webhookUrl = webhookUrl,
                            secret = secret,
                            onSent = { _ -> }
                        )
                        return
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SmsMonitorService",
                        "Failed to parse orderExpiresAt: ${task.orderExpiresAt} — continuing scan", e)
                }
            }

            if (TaskScanner.isScanning(task.taskId)) {
                OrderEventLogger.scanLocked(task.requestId, task.orderNumber, task.taskId)
                return
            }

            AppState.updateOrderScanProgress(task.requestId, 1, TaskScanner.MAX_SCAN_ATTEMPTS, 0)
            OrderEventLogger.scanStarted(task.requestId, task.orderNumber, task.taskId)
            TaskScanner.scanAndReport(context, task, webhookUrl, secret) { result, success ->
                TaskResultCache.put(context, task.taskId, result)
                if (!success) TaskResultCache.incrementRetry(context, task.taskId)
                // سجل نتيجة الفحص في المراقبة
                when (result) {
                    is TaskScanner.ScanResult.Success ->
                        OrderEventLogger.matchFound(task.requestId, task.orderNumber, result.message.transactionId)
                    is TaskScanner.ScanResult.NotFound ->
                        OrderEventLogger.scanNotFound(task.requestId, task.orderNumber, task.taskId, result.reason)
                    is TaskScanner.ScanResult.AmountMismatch ->
                        OrderEventLogger.scanAmountMismatch(task.requestId, task.orderNumber, task.taskId, result.foundAmount, result.expectedAmount)
                    is TaskScanner.ScanResult.Failure ->
                        OrderEventLogger.scanFailed(task.requestId, task.orderNumber, task.taskId, result.reason)
                }
            }
        }

        private fun applyCachedStatus(requestId: String, cached: TaskResultCache.CachedResult) {
            // حالة الكاش تعكس ما أرسله الجهاز — ليس قرار السيرفر.
            // success من الكاش = وجدنا SMS وأرسلناه، لكن رد السيرفر لم يُحفظ بعد.
            // نضع WAITING_CONFIRMATION حتى يرد السيرفر — لا MANUAL_REVIEW.
            // MANUAL_REVIEW محجوز للسيرفر فقط (sender_phone_mismatch, sms_expired).
            val status = when (cached.status) {
                "success"        -> OrderStatus.WAITING_CONFIRMATION
                "amount_mismatch"-> OrderStatus.AMOUNT_MISMATCH
                "not_found"      -> OrderStatus.NOT_FOUND
                "failure"        -> OrderStatus.FAILED
                else             -> OrderStatus.PENDING
            }
            android.util.Log.i("SmsMonitorService", "CACHE_STATUS | order=$requestId cached=${cached.status} → $status")
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
            OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.SYNC_TASKS, details = "count=${tasks.size}")
            AppState.pendingTasks.postValue(tasks)

            TaskScanner.taskResultCallback = { task, result ->
                AppState.onTaskResult(task, result)
            }

            tasks.forEach { task ->
                // ✅ FIX: حماية دقيقة — نتجاهل فقط ما أُكِّد فعلاً محلياً
                // السيرفر يُرسل pending_tasks فقط للطلبات غير المؤكدة → نثق بالسيرفر
                val existingStatus = AppState.getOrders()
                    .firstOrNull { it.requestId == task.requestId }?.status
                if (existingStatus in setOf(OrderStatus.COMPLETED, OrderStatus.CONFIRMED)) {
                    android.util.Log.d("SmsMonitorService",
                        "handlePendingTasks: skipping confirmed order ${task.requestId}")
                    return@forEach
                }
                // إذا كانت الحالة terminal قديمة (EXPIRED/DUPLICATE/FAILED) والسيرفر أعاد إرسالها
                // → امسح الكاش القديم واسمح بالفحص من جديد
                if (existingStatus != null && existingStatus.isTerminal()) {
                    android.util.Log.i("SmsMonitorService",
                        "handlePendingTasks: server re-dispatched ${task.requestId} (${existingStatus.name}) — resetting")
                    AppState.updateOrderStatus(task.requestId, OrderStatus.PENDING)
                    TaskResultCache.remove(context, task.taskId)
                    TaskScanner.clearScanLock(task.taskId)
                }
                // السماح بإعادة الفحص لـ PENDING و SCANNING و MANUAL_REVIEW و NOT_FOUND
                val allowScan = existingStatus == null ||
                    existingStatus in setOf(
                        OrderStatus.PENDING, OrderStatus.SCANNING,
                        OrderStatus.MANUAL_REVIEW, OrderStatus.NOT_FOUND,
                        OrderStatus.EXPIRED, OrderStatus.DUPLICATE, OrderStatus.FAILED
                    )
                if (!allowScan) {
                    android.util.Log.d("SmsMonitorService",
                        "handlePendingTasks: status=${existingStatus?.name} not scannable — skipping")
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
    } // end companion object

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

        val adminLoggedIn = AdminSession.isLoggedIn(this)
        val hasWebhook = !webhookUrl.isNullOrEmpty() && !secret.isNullOrEmpty()

        if (hasWebhook) {
            // ── Webhook mode: Heartbeat يستلم المهام ويبدأ الفحص تلقائياً ──
            heartbeatManager?.stop()
            heartbeatManager = HeartbeatManager(
                this,
                webhookUrl!!,
                secret!!,
                onStatusChange = { connected, message ->
                    updateNotification(if (connected) "متصل ✓" else "غير متصل: $message")
                },
                onPendingTasks = { tasks ->
                    handlePendingTasks(this, tasks, webhookUrl, secret)
                }
            )
            heartbeatManager?.start()

            // ── أيضاً: لو admin مسجل دخول شغّل OrderSyncManager لمزامنة الحالات ──
            if (adminLoggedIn) {
                val adminUrl = SupabaseConfig.getAdminUrl(webhookUrl) ?: ""
                if (adminUrl.isNotEmpty()) {
                    orderSyncManager?.stop()
                    orderSyncManager = OrderSyncManager(this, adminUrl) { success, msg ->
                        if (success) android.util.Log.d("SmsMonitorService", "Admin sync OK: $msg")
                    }
                    orderSyncManager?.start()
                }
            }
        } else if (adminLoggedIn) {
            // ── Admin-only mode: لا webhook — نستخدم Admin API مباشرة ──
            updateNotification("متصل كأدمن ✓")
            val adminUrl = SupabaseConfig.getAdminUrl("") ?: ""
            if (adminUrl.isNotEmpty()) {
                orderSyncManager?.stop()
                orderSyncManager = OrderSyncManager(this, adminUrl) { success, msg ->
                    if (success) updateNotification("مزامنة أدمن ✓")
                }
                orderSyncManager?.start()
            } else {
                updateNotification("أدمن — لا يوجد webhook URL")
            }
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