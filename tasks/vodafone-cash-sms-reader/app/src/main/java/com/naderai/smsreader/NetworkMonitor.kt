package com.naderai.smsreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * يراقب حالة الاتصال ويطلب مزامنة فورية عند عودة الإنترنت.
 *
 * الإصلاحات:
 * - wasAvailable لا يمنع reconnect بعد lost: نُفلّت onAvailable دائماً بعد فقدان الشبكة.
 * - debounce 1.5s لمنع تكرار الـ trigger في ثواني قليلة.
 * - NET_CAPABILITY_VALIDATED: نتأكد من اتصال حقيقي بالإنترنت وليس Wi-Fi بدون إنترنت.
 * - registerNetworkCallback مع NetworkRequest للحصول على VALIDATED بدقة.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"
    private const val RECONNECT_DEBOUNCE_MS = 1_500L

    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    // true = الإنترنت كان متاحاً عند آخر فحص
    private var wasAvailable = false
    // علم: فقدنا الشبكة مرة واحدة على الأقل منذ آخر reconnect → نسمح بـ trigger
    private var lostAtLeastOnce = false

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable {
        val ctx = appContext ?: return@Runnable
        android.util.Log.i(TAG, "Network reconnected — triggering sync & service restart")
        // أعد تشغيل الخدمة أولاً (تُعيد اتصال Heartbeat/Realtime)
        SmsMonitorService.start(ctx)
        // ثم اطلب مزامنة فورية لجلب الطلبات الفائتة
        SyncTriggers.onNetworkAvailable(ctx)
        lostAtLeastOnce = false
    }

    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (callback != null) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                android.util.Log.d(TAG, "onAvailable: wasAvailable=$wasAvailable lostAtLeastOnce=$lostAtLeastOnce")
                // نُفلّت reconnect فقط لو كنا فاقدين الشبكة فعلاً
                if (!wasAvailable || lostAtLeastOnce) {
                    wasAvailable = true
                    scheduleReconnect()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                android.util.Log.d(TAG, "onLost")
                wasAvailable = false
                lostAtLeastOnce = true
                handler.removeCallbacks(reconnectRunnable)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, caps)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && (!wasAvailable || lostAtLeastOnce)) {
                    android.util.Log.d(TAG, "onCapabilitiesChanged: validated=true, triggering reconnect")
                    wasAvailable = true
                    scheduleReconnect()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                wasAvailable = false
                lostAtLeastOnce = true
                handler.removeCallbacks(reconnectRunnable)
            }
        }

        callback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to register network callback: ${e.message}")
        }

        // تحديد الحالة الأولية
        val activeNet = cm.activeNetwork
        val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
        wasAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        android.util.Log.d(TAG, "NetworkMonitor started — initially available=$wasAvailable")
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DEBOUNCE_MS)
    }

    fun stop() {
        handler.removeCallbacks(reconnectRunnable)
        callback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to unregister: ${e.message}")
            }
        }
        callback = null
        connectivityManager = null
        appContext = null
    }
}
