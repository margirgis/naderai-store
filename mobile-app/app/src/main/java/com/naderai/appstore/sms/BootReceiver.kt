package com.naderai.appstore.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * يُعيد تشغيل SmsReaderService تلقائياً بعد إعادة تشغيل الجهاز أو تحديث التطبيق.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootReceiver", "Boot/Update detected — starting SmsReaderService")
            SmsReaderService.start(context)
        }
    }
}
