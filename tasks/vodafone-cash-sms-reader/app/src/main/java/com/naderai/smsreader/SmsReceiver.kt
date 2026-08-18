package com.naderai.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val secret = prefs.getString(MainActivity.KEY_SECRET, null)
        val deviceId = HeartbeatManager.getDeviceId(context)

        for (sms in messages) {
            val body = sms.messageBody ?: continue
            val sender = sms.displayOriginatingAddress ?: "unknown"

            // فقط رسائل الاستلام الرسمية من فودافون كاش
            if (!SmsParser.isOfficialReceivedMessage(body)) {
                Log.d(TAG, "Ignored non-official/received SMS from $sender")
                continue
            }

            val parsed = SmsParser.parseReceived(body)
            Log.d(TAG, "VF-Cash SMS: phone=${parsed.senderPhone}, name=${parsed.senderName}, amount=${parsed.amount}, txId=${parsed.transactionId}")

            // P2 FIX: حفظ الرسالة في الطابور المحلي فوراً قبل أي شيء آخر.
            // هذا يحل سيناريو: SMS تصل قبل وصول task من السيرفر.
            LocalSmsQueue.push(context, LocalSmsQueue.QueuedSms(
                transactionId = parsed.transactionId,
                senderPhone   = parsed.senderPhone,
                senderName    = parsed.senderName,
                amount        = parsed.amount,
                receiverWallet = parsed.receiverWallet,
                smsBody       = body,
                receivedAt    = System.currentTimeMillis()
            ))

            if (!webhookUrl.isNullOrEmpty() && !secret.isNullOrEmpty()) {
                val payload = mapOf(
                    "sender_phone"   to (parsed.senderPhone ?: sender),
                    "sender_name"    to (parsed.senderName ?: ""),
                    "amount"         to (parsed.amount?.toString() ?: ""),
                    "transaction_id" to (parsed.transactionId ?: ""),
                    "receiver_wallet" to (parsed.receiverWallet ?: ""),
                    "message"        to body,
                    "received_at"    to System.currentTimeMillis().toString(),
                    "device_id"      to deviceId
                )
                WebhookSender.send(webhookUrl, secret, payload) { success, msg ->
                    Log.d(TAG, "Webhook result: $success — $msg")
                }
            }
        }

        // فحص الطلبات المعلقة فوراً عند استلام رسالة فودافون كاش
        SmsMonitorService.onNewSmsReceived(context)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
