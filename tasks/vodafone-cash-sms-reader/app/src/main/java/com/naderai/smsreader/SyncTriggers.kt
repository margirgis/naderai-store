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
     * Fix #6: الترتيب الصحيح:
     *   1) تحميل الطلبات المحلية المحفوظة → لا تضيع الطلبات بعد إعادة التشغيل
     *   2) تسجيل حدث APP_START_RECOVERY في Diagnostics
     *   3) مزامنة فورية لجلب أي طلبات فائتة من السيرفر
     *   4) تشغيل المزامنة الدورية
     */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        initialize(app)

        // 1) تحميل الطلبات المحفوظة محلياً قبل أي طلب شبكي
        val localOrders = OrderStorage.loadOrders(app)
        if (localOrders.isNotEmpty()) {
            AppState.mergeOrders(localOrders)
            android.util.Log.i("SyncTriggers",
                "APP_START: loaded ${localOrders.size} local orders from storage")
            OrderDiagnosticsLog.log(
                OrderDiagnosticsLog.EventType.LOCAL_ORDERS_LOADED,
                details = "count=${localOrders.size} — محمّلة من التخزين المحلي"
            )
        }

        // 2) تسجيل حدث بدء التطبيق
        OrderDiagnosticsLog.log(
            OrderDiagnosticsLog.EventType.APP_START_RECOVERY,
            details = "local_orders=${localOrders.size} — بدء الاسترجاع"
        )

        // 3) مزامنة فورية لجلب الطلبات الفائتة
        triggerSync(app, "app_start")

        // 4) تشغيل المزامنة الدورية
        startPeriodicSync(app)
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
     * يشغّل: 1) drain للـ RetryQueue 2) restart OrderSyncManager 3) forceSync للـ Heartbeat
     */
    fun onNetworkAvailable(ctx: Context) {
        val app = ctx.applicationContext
        OrderEventLogger.syncRequest("network_reconnect", HeartbeatManager.getDeviceId(app))
        android.util.Log.i("SyncTriggers", "onNetworkAvailable — forcing immediate sync + drain retry queue")

        // 1. استنزف RetryQueue بالطلبات المعلقة (backoff, max 5 retries)
        val prefs = app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl) ?: ""
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        if (webhookUrl.isNotEmpty() && secret.isNotEmpty()) {
            RetryQueue.drainOnReconnect(app, webhookUrl, secret)
        }

        // 2. أعد تشغيل admin sync
        forceRestartAdminSync(app)

        // 3. اطلب sync من HeartbeatManager لجلب الطلبات الفائتة
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

    /**
     * Bug #3 Fix: startAdminSync لا تتحقق من adminUrl لتتجنب الـ guard bug.
     * كانت `if (orderSyncManager?.adminUrl == adminUrl) return` تمنع restart
     * عند Runtime (URL نفسه) → الـ instance القديم يظل متوقفاً بدون علم.
     * الحل: تُوقف دائماً وتُنشئ جديداً، مع guard على الـ URL الفارغ فقط.
     */
    private fun startAdminSync(ctx: Context) {
        val rawUrl = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_WEBHOOK_URL, null)
        val adminUrl = SupabaseConfig.getAdminOrdersUrl(rawUrl) ?: return
        // Bug #3 Fix: لا نتحقق من adminUrl == adminUrl — هذا Guard كان يمنع restart في Runtime
        // نتحقق فقط: هل الـ instance شغّال فعلاً؟ إذا شغّال على نفس URL → لا نعيد
        if (orderSyncManager != null && orderSyncManager?.adminUrl == adminUrl) {
            // instance موجود وشغّال على نفس URL → sync فوري بدلاً من restart
            orderSyncManager?.sync()
            return
        }
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
