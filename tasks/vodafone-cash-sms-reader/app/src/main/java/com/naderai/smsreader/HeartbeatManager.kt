package com.naderai.smsreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build

class HeartbeatManager(
    private val context: Context,
    private val webhookUrl: String,
    private val secret: String,
    private val onStatusChange: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId: String
<<<<<<< HEAD
        get() = Companion.getDeviceId(context)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_DEVICE_ID = "device_id"

        fun getDeviceId(context: Context): String {
=======
        get() {
>>>>>>> e74a468 (Initial miaoda project setup with React TypeScript Vite template 24bcfe376599d1be4d3f212bf5d9cffa4aa58f41 no sync)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?: (System.currentTimeMillis().toString())
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
<<<<<<< HEAD
    }
=======
>>>>>>> e74a468 (Initial miaoda project setup with React TypeScript Vite template 24bcfe376599d1be4d3f212bf5d9cffa4aa58f41 no sync)

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        sendHeartbeat()
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(heartbeatRunnable)
    }

    private fun sendHeartbeat() {
        val payload = mapOf(
            "action" to "heartbeat",
            "device_id" to deviceId,
            "device_model" to (Build.MODEL ?: "Unknown"),
            "device_name" to (Build.DEVICE ?: "Unknown"),
<<<<<<< HEAD
            "app_version" to "1.0.1"
=======
            "app_version" to "1.0.0"
>>>>>>> e74a468 (Initial miaoda project setup with React TypeScript Vite template 24bcfe376599d1be4d3f212bf5d9cffa4aa58f41 no sync)
        )

        WebhookSender.send(webhookUrl, secret, payload) { success, message ->
            onStatusChange(success, if (success) "متصل" else "غير متصل: $message")
        }
    }
<<<<<<< HEAD
=======

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_DEVICE_ID = "device_id"
    }
>>>>>>> e74a468 (Initial miaoda project setup with React TypeScript Vite template 24bcfe376599d1be4d3f212bf5d9cffa4aa58f41 no sync)
}
