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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("جاري الاتصال..."))
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
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

    private fun handlePendingTasks(context: Context, tasks: List<TaskScanner.Task>, webhookUrl: String, secret: String) {
        if (tasks.isEmpty()) return
        android.util.Log.d("SmsMonitorService", "Received ${tasks.size} pending tasks")
        // Update global state for UI
        AppState.pendingTasks.postValue(tasks)
        tasks.forEach { task ->
            // Set scanner callback so UI gets updated
            TaskScanner.taskResultCallback = { taskId, result ->
                AppState.onTaskResult(task, result)
                // If offline, enqueue for retry
            }
            TaskScanner.scanAndReport(context, task, webhookUrl, secret)
        }
        updateNotification("يفحص ${tasks.size} طلب...")
    }

    override fun onDestroy() {
        heartbeatManager?.stop()
        heartbeatManager = null
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يُبقي التطبيق يعمل في الخلفية لاستقبال رسائل فودافون كاش"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.naderai.smsreader.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsMonitorService::class.java))
        }
    }
}
