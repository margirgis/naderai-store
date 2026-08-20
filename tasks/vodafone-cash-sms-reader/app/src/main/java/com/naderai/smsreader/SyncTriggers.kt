package com.naderai.smsreader

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * نقطة مركزية لتشغيل المزامنة في جميع الحالات المطلوبة:
 * - بدء التطبيق
 * - تسجيل الدخول / تسجيل الجهاز
 * - إعادة الاتصال بالإنترنت
 * - Heartbeat
 * - مزامنة دورية
 *
 * لا تُنشئ هذه الكائن واجهات جديدة؛ بل تُستخدم الإدارات الموجودة.
 */
object SyncTriggers {

    private val handler = Handler(Looper.getMainLooper())
    private var orderSyncManager: OrderSyncManager? = null

    private const val PERIODIC_SYNC_INTERVAL_MS = 60_000L
    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            val ctx = context ?: return
            triggerSync(ctx, "periodic")
            handler.postDelayed(this, PERIODIC_SYNC_INTERVAL_MS)
        }
    }

    private var context: Context? = null
    private var started = false

    /**
     * يُستدعى مرة واحدة من [Application.onCreate].
     */
    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        OrderEventLogger.init { HeartbeatManager.getDeviceId(ctx.applicationContext) }
    }

    /**
     * يُستدعى عند فتح التطبيق (MainActivity.onCreate).
     */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        initialize(app)
        startPeriodicSync(app)
        triggerSync(app, "app_start")
    }

    /**
     * يُستدعى بعد نجاح تسجيل الدخول كأدمن أو تسجيل الجهاز.
     */
    fun onLogin(ctx: Context) {
        val app = ctx.applicationContext
        initialize(app)
        startPeriodicSync(app)
        triggerSync(app, "login")
        // تأكد من تشغيل خدمة SMS
        SmsMonitorService.start(app)
    }

    /**
     * يُستدعى عند عودة الاتصال بالإنترنت.
     * يجب أن يُشغّل مزامنة فورية بغض النظر عن حالة orderSyncManager الحالية.
     */
    fun onNetworkAvailable(ctx: Context) {
        val app = ctx.applicationContext
        OrderEventLogger.syncRequest("network_reconnect", HeartbeatManager.getDeviceId(app))
        android.util.Log.i("SyncTriggers", "onNetworkAvailable — forcing immediate sync for missed tasks")
        // نُعيد تشغيل الـ admin sync بقوة (بغض النظر عن adminUrl المخزّن)
        forceRestartAdminSync(app)
        // إذا كان هناك webhook مُعرَّف، اطلب sync من HeartbeatManager أيضاً
        val prefs = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl) ?: ""
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        if (webhookUrl.isNotEmpty() && secret.isNotEmpty()) {
            SmsMonitorService.forceSync(app)
        }
    }

    /**
     * مزامنة فورية واحدة. تستخدم الـ Heartbeat إذا كان الجهاز مسجّلاً بـ Webhook،
     * أو OrderSyncManager إذا كان الأدمن مسجّلاً.
     */
    fun triggerSync(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        OrderEventLogger.syncRequest(reason, HeartbeatManager.getDeviceId(app))
        android.util.Log.d("SyncTriggers", "triggerSync: $reason")

        val prefs = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl) ?: ""
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        val adminLoggedIn = AdminSession.isLoggedIn(app)

        if (adminLoggedIn) {
            startAdminSync(app)
            orderSyncManager?.sync()
        }

        if (webhookUrl.isNotEmpty() && secret.isNotEmpty()) {
            SmsMonitorService.forceSync(app)
        }
    }

    private fun startAdminSync(ctx: Context) {
        val rawUrl = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_WEBHOOK_URL, null)
        val adminUrl = SupabaseConfig.getAdminOrdersUrl(rawUrl) ?: return
        if (orderSyncManager?.adminUrl == adminUrl) return
        orderSyncManager?.stop()
        orderSyncManager = OrderSyncManager(ctx, adminUrl) { _, _ -> }
        orderSyncManager?.start()
    }

    /**
     * يُعيد تشغيل admin sync بقوة (حتى لو adminUrl لم يتغيّر) — يُستخدم عند reconnect.
     * الفرق عن startAdminSync: لا يتحقق من adminUrl المخزّن، يُوقف ويُعيد دائماً.
     */
    private fun forceRestartAdminSync(ctx: Context) {
        val rawUrl = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_WEBHOOK_URL, null)
        val adminUrl = SupabaseConfig.getAdminOrdersUrl(rawUrl) ?: return
        android.util.Log.d("SyncTriggers", "forceRestartAdminSync: restarting OrderSyncManager")
        orderSyncManager?.stop()
        orderSyncManager = OrderSyncManager(ctx, adminUrl) { _, _ -> }
        orderSyncManager?.start()
        // sync فوري إضافي لجلب الطلبات الفائتة
        orderSyncManager?.sync()
    }

    private fun startPeriodicSync(ctx: Context) {
        if (started) return
        started = true
        handler.removeCallbacks(periodicSyncRunnable)
        handler.post(periodicSyncRunnable)
    }

    fun stopPeriodicSync() {
        handler.removeCallbacks(periodicSyncRunnable)
        started = false
    }
}
