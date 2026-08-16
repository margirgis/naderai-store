package com.naderai.smsreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * يراقب حالة الاتصال ويطلب مزامنة فورية عند عودة الإنترنت.
 * مفيد للحالات: Realtime disconnect, App in background, reconnect, missed event.
 */
object NetworkMonitor {

    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wasAvailable = false
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (!wasAvailable) {
                    wasAvailable = true
                    android.util.Log.d("NetworkMonitor", "Network became available")
                    // تأخير بسيط للتأكد من استقرار الاتصال
                    handler.postDelayed({
                        SyncTriggers.onNetworkAvailable(context)
                    }, 500)
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                wasAvailable = false
                android.util.Log.d("NetworkMonitor", "Network lost")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val available = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (available && !wasAvailable) {
                    wasAvailable = true
                    handler.postDelayed({
                        SyncTriggers.onNetworkAvailable(context)
                    }, 500)
                }
            }
        }
        callback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            android.util.Log.e("NetworkMonitor", "Failed to register network callback: ${e.message}")
        }

        // تحديد الحالة الأولية
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        wasAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun stop(context: Context) {
        callback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                android.util.Log.e("NetworkMonitor", "Failed to unregister network callback: ${e.message}")
            }
        }
        callback = null
        connectivityManager = null
    }
}
