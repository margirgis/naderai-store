package com.naderai.appstore.sms

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * معلومات الجهاز — Device ID مستقر بين التحديثات.
 */
object DeviceInfo {

    private const val PREFS_NAME = "naderai_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: System.currentTimeMillis().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    fun getDeviceName(): String = Build.DEVICE ?: "Unknown"
    fun getAndroidVersion(): String = Build.VERSION.RELEASE ?: "Unknown"
}
