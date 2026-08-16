package com.naderai.app.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.naderai.app.MainActivity
import com.naderai.app.R

/**
 * Foreground Service يشتغل في الخلفية طوال الوقت.
 * مسؤوليات:
 * 1. تشغيل HeartbeatManager (تسجيل الجهاز + heartbeat كل 120 ث)
 * 2. عند استقبال مهمة من السيرفر → يفحص SMS الوارد → يُرسل النتيجة
 * 3. يستقبل رسائل SMS من SmsReceiver مباشرة
 */
class SmsReaderService : Service() {

    private val TAG = "SmsReaderService"
    private var heartbeatManager: HeartbeatManager? = null
    private var webhookUrl: String? = null
    private var webhookSecret: String? = null
    private val pendingTasks = mutableListOf<TaskScanner.Task>()

    companion object {
        private const val CHANNEL_ID = "naderai_sms_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "naderai_config"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_WEBHOOK_SECRET = "webhook_secret"

        fun start(context: Context) {
            val intent = Intent(context, SmsReaderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsReaderService::class.java))
        }

        /** يُستدعى من SmsReceiver عند وصول SMS جديد */
        fun onSmsReceived(context: Context, sender: String, body: String) {
            val intent = Intent(context, SmsReaderService::class.java).apply {
                action = "SMS_RECEIVED"
                putExtra("sender", sender)
                putExtra("body", body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("يراقب رسائل Vodafone Cash…"))
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SMS_RECEIVED" -> {
                val sender = intent.getStringExtra("sender") ?: ""
                val body = intent.getStringExtra("body") ?: ""
                handleIncomingSms(sender, body)
            }
            else -> {
                initHeartbeatIfNeeded()
            }
        }
        return START_STICKY
    }

    private fun initHeartbeatIfNeeded() {
        if (heartbeatManager != null) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_WEBHOOK_URL, null) ?: run {
            // رابط افتراضي من Supabase
            val base = "https://ccimllgqdxuvymdeikmn.supabase.co"
            "$base/functions/v1/wallet-auto-confirm"
        }
        val secret = prefs.getString(KEY_WEBHOOK_SECRET, null) ?: ""
        webhookUrl = url
        webhookSecret = secret

        heartbeatManager = HeartbeatManager(
            context = this,
            webhookUrl = url,
            secret = secret,
            onStatusChange = { connected, msg ->
                Log.d(TAG, "Status: connected=$connected, msg=$msg")
                updateNotification(if (connected) "متصل ✓ — $msg" else "⚠️ $msg")
            },
            onPendingTasks = { tasks ->
                pendingTasks.clear()
                pendingTasks.addAll(tasks)
                processPendingTasksFromInbox()
            }
        )
        heartbeatManager?.start()
        Log.d(TAG, "HeartbeatManager initialized — url=$url")
    }

    /** عند وصول SMS جديد مباشرة عبر BroadcastReceiver */
    private fun handleIncomingSms(sender: String, body: String) {
        Log.d(TAG, "Incoming SMS from $sender, body=${body.take(80)}")
        if (!TaskScanner.isOfficialVodafoneCashMessage(body)) {
            Log.d(TAG, "Not a Vodafone Cash message — skipping")
            return
        }
        // طابق مع المهام المعلقة
        val matched = pendingTasks.firstOrNull { task -> TaskScanner.matchSmsToTask(body, task) }
        if (matched != null) {
            Log.d(TAG, "SMS matched task ${matched.taskId}")
            sendTaskResult(matched, body, success = true)
            pendingTasks.remove(matched)
        } else {
            Log.d(TAG, "SMS received but no matching task found")
        }
    }

    /** يفحص صندوق الرسائل الوارد بحثاً عن تطابق مع المهام المعلقة */
    private fun processPendingTasksFromInbox() {
        if (pendingTasks.isEmpty()) return
        Log.d(TAG, "Processing ${pendingTasks.size} pending tasks from inbox")

        try {
            val cursor: Cursor? = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null, null,
                "date DESC LIMIT 100"
            )
            val smsList = mutableListOf<Pair<String, String>>() // sender to body
            cursor?.use { c ->
                val bodyIdx = c.getColumnIndex("body")
                val addrIdx = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    smsList.add(c.getString(addrIdx) to c.getString(bodyIdx))
                }
            }

            val toRemove = mutableListOf<TaskScanner.Task>()
            for (task in pendingTasks) {
                for ((_, body) in smsList) {
                    if (TaskScanner.matchSmsToTask(body, task)) {
                        Log.d(TAG, "Inbox match found for task ${task.taskId}")
                        sendTaskResult(task, body, success = true)
                        toRemove.add(task)
                        break
                    }
                }
            }
            pendingTasks.removeAll(toRemove)
        } catch (e: Exception) {
            Log.e(TAG, "processPendingTasksFromInbox error: ${e.message}", e)
        }
    }

    private fun sendTaskResult(task: TaskScanner.Task, smsBody: String, success: Boolean, reason: String? = null) {
        val url = webhookUrl ?: return
        val secret = webhookSecret ?: ""
        val payload = TaskScanner.buildResultPayload(task.taskId, smsBody, success, reason)
        WebhookSender.sendJson(url, secret, payload) { ok, msg ->
            Log.d(TAG, "Task result sent: ok=$ok, msg=$msg")
        }
    }

    // ── Notification helpers ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatManager?.stop()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}
