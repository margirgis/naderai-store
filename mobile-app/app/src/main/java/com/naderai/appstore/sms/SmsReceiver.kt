package com.naderai.appstore.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * BroadcastReceiver يستقبل رسائل SMS الواردة.
 * يُمررها فوراً لـ SmsReaderService للمعالجة.
 * Priority=999 يضمن استلامها قبل التطبيقات الأخرى.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // جمع أجزاء الرسالة (multipart SMS)
            val senderAddress = messages[0].originatingAddress ?: ""
            val fullBody = messages.joinToString("") { it.messageBody ?: "" }

            Log.d("SmsReceiver", "SMS from $senderAddress — ${fullBody.take(60)}")

            // تمرير للخدمة للمعالجة
            SmsReaderService.onSmsReceived(context, senderAddress, fullBody)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error processing SMS: ${e.message}", e)
        }
    }
}
