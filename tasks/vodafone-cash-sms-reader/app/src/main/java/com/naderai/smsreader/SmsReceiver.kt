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
            if (!TaskScanner.isOfficialVodafoneCashMessage(body)) {
                Log.d(TAG, "Ignored non-official/received SMS from $sender")
                continue
            }

            val parsed = parseSmsBody(body)
            Log.d(TAG, "VF-Cash SMS: phone=${parsed.senderPhone}, name=${parsed.senderName}, amount=${parsed.amount}, txId=${parsed.transactionId}")

            // P2 FIX: حفظ الرسالة في الطابور المحلي فوراً قبل أي شيء آخر.
            // هذا يحل سيناريو: SMS تصل قبل وصول task من السيرفر.
            val normalizedPhone = parsed.senderPhone?.let { normalizeEgyptianPhone(it) }
            LocalSmsQueue.push(context, LocalSmsQueue.QueuedSms(
                transactionId = parsed.transactionId,
                senderPhone   = normalizedPhone,
                senderName    = parsed.senderName,
                amount        = parsed.amount,
                receiverWallet = null,
                smsBody       = body,
                receivedAt    = System.currentTimeMillis()
            ))

            if (!webhookUrl.isNullOrEmpty() && !secret.isNullOrEmpty()) {
                val payload = mapOf(
                    "sender_phone"   to (normalizedPhone ?: sender),
                    "sender_name"    to (parsed.senderName ?: ""),
                    "amount"         to (parsed.amount?.toString() ?: ""),
                    "transaction_id" to (parsed.transactionId ?: ""),
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

        // Sender phone: "من 01152210028؛" or "من رقم 01222692182"
        val phoneRegexes = listOf(
            Regex("من\\s+رقم\\s*(\\+?01[0-9]{9})"),
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

        // Sender name: "بإسم AHMED REDA على" / "بإسم نادر اكرام راغب مينا على"
        val nameRegexes = listOf(
            Regex("بإسم\\s+([A-Za-z][A-Za-z0-9 ]{1,50})\\s*على"),
            Regex("بإسم\\s+([\\u0600-\\u06FF][\\u0600-\\u06FF ]{1,50})\\s*على"),
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

        // Transaction ID — "رقم العملية: 022768543034"
        val txRegexes = listOf(
            Regex("رقم\\s+العملية[:\\s]+([0-9]{9,20})"),
            Regex("كود المعاملة[:\\s]+([A-Za-z0-9]+)"),
            Regex("transaction\\s*id[:\\s]+([A-Za-z0-9]+)", RegexOption.IGNORE_CASE),
            Regex("\\b([0-9]{12,20})\\b")
        )
        var transactionId: String? = null
        for (re in txRegexes) {
            val m = re.find(text)
            if (m != null) { transactionId = m.groupValues[1]; break }
        }

        return ParsedSms(senderPhone, senderName, amount, transactionId)
    }

    private fun normalizeEgyptianPhone(raw: String): String {
        val digits = raw.replace(Regex("\\D"), "")
        return when {
            digits.length == 10 && digits.startsWith("1") -> digits
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") && digits[2] == '1' -> digits.substring(2)
            digits.length == 13 && digits.startsWith("20") && digits[3] == '1' -> digits.substring(3)
            else -> digits
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
