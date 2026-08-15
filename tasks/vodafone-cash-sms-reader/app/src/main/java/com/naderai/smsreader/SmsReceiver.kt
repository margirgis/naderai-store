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
        val webhookUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null) ?: return
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: return
        val deviceId = HeartbeatManager.getDeviceId(context)

        for (sms in messages) {
            val body = sms.messageBody ?: continue
            val sender = sms.displayOriginatingAddress ?: "unknown"

            // Only forward received Vodafone Cash messages (not outgoing transfers)
            if (!TaskScanner.isOfficialVodafoneCashMessage(body)) {
                Log.d(TAG, "Ignored non-official/received SMS from $sender")
                continue
            }

            val parsed = parseSmsBody(body)
            Log.d(TAG, "Parsed: phone=${parsed.senderPhone}, name=${parsed.senderName}, amount=${parsed.amount}, txId=${parsed.transactionId}")

            val payload = mapOf(
                "sender_phone" to (parsed.senderPhone ?: sender),
                "sender_name" to (parsed.senderName ?: ""),
                "amount" to (parsed.amount?.toString() ?: ""),
                "transaction_id" to (parsed.transactionId ?: ""),
                "message" to body,
                "received_at" to System.currentTimeMillis().toString(),
                "device_id" to deviceId
            )

            WebhookSender.send(webhookUrl, secret, payload) { success, msg ->
                Log.d(TAG, "Webhook result: $success — $msg")
            }
        }

        // فحص الطلبات المعلقة عند استلام رسالة فودافون كاش جديدة
        SmsMonitorService.onNewSmsReceived(context)
    }

    data class ParsedSms(
        val senderPhone: String?,
        val senderName: String?,
        val amount: Double?,
        val transactionId: String?
    )

    private fun parseSmsBody(text: String): ParsedSms {
        // Amount: Arabic "مبلغ 600.22 جنيه" or English "received EGP 600.22"
        val amountRegexes = listOf(
            Regex("مبلغ\\s*([\\d,]+\\.?\\d{0,2})\\s*جنيه"),
            Regex("استلمت\\s+(?:من\\s+.*?\\s+)?مبلغ\\s*([\\d,]+\\.?\\d{0,2})"),
            Regex("received\\s+(?:egp\\s+)?([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("egp\\s+([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("([\\d,]+\\.\\d{1,2})\\s*جنيه")
        )
        var amount: Double? = null
        for (re in amountRegexes) {
            val m = re.find(text)
            if (m != null) {
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
                if (v != null && v > 0) { amount = v; break }
            }
        }

        // Sender phone: "من 01152210028؛" (Arabic semicolon separator) or English from
        val phoneRegexes = listOf(
            Regex("من\\s*(\\+?01[0-9]{9})"),
            Regex("from\\s*(\\+?\\d[\\d ]{8,14})", RegexOption.IGNORE_CASE),
            Regex("(\\+?20\\s*1\\d{9})"),
            Regex("(01[0-9]{9})")
        )
        var senderPhone: String? = null
        for (re in phoneRegexes) {
            val m = re.find(text)
            if (m != null) { senderPhone = m.groupValues[1].replace("\\s".toRegex(), ""); break }
        }

        // Sender name: "بإسم AHMED REDAعلى" (no space before على) or "from [Name] on"
        val nameRegexes = listOf(
            Regex("بإسم\\s+([A-Za-z][A-Za-z0-9 ]{1,30})\\s*على"),
            Regex("باسم\\s+([\\u0600-\\u06FF ]{2,30})\\s+"),
            Regex("from\\s+([A-Za-z][A-Za-z ]{1,30})\\s+on", RegexOption.IGNORE_CASE)
        )
        var senderName: String? = null
        for (re in nameRegexes) {
            val m = re.find(text)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (!candidate.matches(Regex("\\d+"))) { senderName = candidate; break }
            }
        }

        // Transaction ID
        val txRegexes = listOf(
            Regex("كود المعاملة[:\\s]+([A-Za-z0-9]+)"),
            Regex("transaction\\s*id[:\\s]+([A-Za-z0-9]+)", RegexOption.IGNORE_CASE),
            Regex("رقم العملية[:\\s]+([A-Za-z0-9]+)"),
            Regex("\\b([A-Z]{2,}[0-9]{4,})\\b")
        )
        var transactionId: String? = null
        for (re in txRegexes) {
            val m = re.find(text)
            if (m != null) { transactionId = m.groupValues[1]; break }
        }

        return ParsedSms(senderPhone, senderName, amount, transactionId)
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
