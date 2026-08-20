package com.naderai.smsreader

import android.app.Application
import androidx.lifecycle.Observer

/**
 * Application class تحافظ على حالة الطلبات محلياً.
 * يحفظ أي تغيير في AppState.orders في SharedPreferences فوراً.
 */
class NaderAiApplication : Application() {

    private val ordersObserver = Observer<List<OrderItem>> { orders ->
        try {
            OrderStorage.saveOrders(this, orders)
        } catch (e: Exception) {
            android.util.Log.e("NaderAiApplication", "Failed to persist orders: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        // مسك كل الأخطاء اللي ممكن تحصل قبل ما يتفتح التطبيق
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("NaderAiApplication", "Uncaught crash on ${thread.name}", throwable)
            CrashLog.write(this, throwable)
            // نفضل بنفس الـ default handler عشان النظام يعرض التعطل بشكل طبيعي
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }

        // P0 FIX: NEVER wipe pending orders on app update.
        OrderStorage.markVersion(this, BuildConfig.VERSION_NAME)

        // تحميل الطلبات المحلية عند بدء التطبيق
        try {
            val cached = OrderStorage.loadOrders(this)
            if (cached.isNotEmpty()) {
                AppState.setOrders(cached)
            }
        } catch (e: Exception) {
            android.util.Log.e("NaderAiApplication", "Failed to load cached orders: ${e.message}")
        }

        // تهيئة الإشعارات الدائمة (تُحمّل من SharedPreferences)
        AppState.initNotifications(this)

        // تجهيز السجلات المنظّمة
        OrderEventLogger.init { HeartbeatManager.getDeviceId(this) }

        // حفظ أي تغيير لاحق
        AppState.orders.observeForever(ordersObserver)
    }
}
