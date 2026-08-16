package com.naderai.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يُعيد تشغيل الـ ForegroundService تلقائياً بعد إعادة تشغيل الجهاز
 * أو بعد تحديث التطبيق.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // تشغيل الخدمة فقط إذا كانت الإعدادات موجودة
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val url = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
            val secret = prefs.getString(MainActivity.KEY_SECRET, null)
            if (!url.isNullOrEmpty() && !secret.isNullOrEmpty()) {
                SmsMonitorService.start(context)
            }
        }
    }
}
