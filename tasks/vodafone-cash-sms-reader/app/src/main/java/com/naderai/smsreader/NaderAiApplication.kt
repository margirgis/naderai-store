package com.naderai.smsreader

import android.app.Application
import androidx.lifecycle.Observer
import java.util.concurrent.Executors

/**
 * Application class تحافظ على حالة الطلبات محلياً.
 * Fix #1: saveOrders/loadOrders تعمل على IO thread — لا تُعيق الـ Main Thread.
 */
class NaderAiApplication : Application() {

    // IO Executor مشترك لكل عمليات القراءة/الكتابة
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "naderai-io").apply { isDaemon = true }
    }

    // Debounce: تجنّب كتابة متكررة عند تحديثات متتالية سريعة
    private var lastSaveMs = 0L
    private val SAVE_DEBOUNCE_MS = 300L
    private val saveRunnable = Runnable {
        val snapshot = AppState.getOrders()
        try {
            OrderStorage.saveOrders(this, snapshot)
        } catch (e: Exception) {
            android.util.Log.e("NaderAiApplication", "Failed to persist orders: ${e.message}")
        }
    }

    private val ordersObserver = Observer<List<OrderItem>> { _ ->
        // Fix #1: لا نكتب على Main Thread — نُجدوِل على IO thread مع debounce 300ms
        val now = System.currentTimeMillis()
        if (now - lastSaveMs < SAVE_DEBOUNCE_MS) return@Observer
        lastSaveMs = now
        ioExecutor.execute(saveRunnable)
    }

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("NaderAiApplication", "Uncaught crash on ${thread.name}", throwable)
            CrashLog.write(this, throwable)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }

        OrderStorage.markVersion(this, BuildConfig.VERSION_NAME)

        // Fix #1: loadOrders على IO thread — لا يُعيق Application.onCreate
        ioExecutor.execute {
            try {
                val cached = OrderStorage.loadOrders(this)
                if (cached.isNotEmpty()) {
                    AppState.setOrders(cached)
                    android.util.Log.d("NaderAiApplication", "Loaded ${cached.size} orders from storage (IO thread)")
                }
            } catch (e: Exception) {
                android.util.Log.e("NaderAiApplication", "Failed to load cached orders: ${e.message}")
            }
        }

        AppState.initNotifications(this)
        OrderEventLogger.init { HeartbeatManager.getDeviceId(this) }

        // حفظ أي تغيير لاحق على IO thread مع debounce
        AppState.orders.observeForever(ordersObserver)
    }
}
