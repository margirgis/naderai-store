package com.naderai.smsreader

import android.content.Context
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Scans device SMS inbox for a task's matching Vodafone Cash message.
 */
object TaskScanner {

    private const val TAG = "TaskScanner"
    private const val MAX_SCAN_ATTEMPTS = 3
    private const val SCAN_INTERVAL_MS = 20_000L

    private val KEYWORDS = listOf(
        "فودافون كاش", "vodafone cash", "استلمت", "لقد استلمت",
        "تم استلام", "received egp", "you have received", "تحويل", "مبلغ"
    )

    data class Task(
        val taskId: String,
        val requestId: String,
        val amountRequested: Double,
        val senderPhoneRequested: String?,
        val senderNameRequested: String?,
        val fingerprintAmount: Double?,
        val creditsAmount: Double?,
        // بيانات كاملة من الموقع
        val orderNumber: Long?,
        val creditsRequested: Int?,
        val customerEmail: String?,
        val customerPhone: String?,
        val paymentMethod: String?,
        val requestCreatedAt: String?
    )

    data class ParsedSms(
        val senderPhone: String?,
        val senderName: String?,
        val amount: Double?,
        val transactionId: String?,
        val body: String,
        val date: Long,
        val receiverWallet: String? = null
    )

    /**
     * يفحص صندوق الرسائل كل 20 ثانية — حد أقصى 3 مرات (دقيقة واحدة).
     * لو لقى تطابق يبعت النتيجة فوراً. لو انتهت المحاولات يبعت آخر نتيجة.
     * بيبعث تحديث للـ UI بعدد المحاولة والعداد التنازلي.
     */
    fun scanAndReport(
        context: Context,
        task: Task,
        webhookUrl: String,
        secret: String,
        onResult: ((ScanResult, Boolean) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var attempt = 0
            var lastResult: ScanResult = ScanResult.NotFound("لم يبدأ الفحص")
            while (attempt < MAX_SCAN_ATTEMPTS) {
                attempt++
                // تحديث UI: المحاولة الحالية
                AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, 0)
                Log.d(TAG, "Scan attempt $attempt/${MAX_SCAN_ATTEMPTS} for task ${task.taskId}")
                lastResult = scanInbox(context, task)
                if (lastResult is ScanResult.Success) break

                if (attempt < MAX_SCAN_ATTEMPTS) {
                    // عداد تنازلي 20 ثانية مع تحديث UI كل ثانية
                    var remaining = (SCAN_INTERVAL_MS / 1000).toInt()
                    while (remaining > 0) {
                        AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, remaining)
                        kotlinx.coroutines.delay(1_000L)
                        remaining--
                    }
                }
            }
            // إرسال النتيجة النهائية مرة واحدة فقط
            AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, 0)
            sendTaskResult(context, task, lastResult, webhookUrl, secret) { success ->
                onResult?.invoke(lastResult, success)
            }
        }
    }

    private fun scanInbox(context: Context, task: Task): ScanResult {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return ScanResult.Failure("إذن قراءة الرسائل غير ممنوح")
        }

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            null,
            null,
            Telephony.Sms.DATE + " DESC"
        ) ?: return ScanResult.Failure("لا يمكن قراءة صندوق الرسائل")

        val candidates = mutableListOf<ScannedMessage>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "unknown"
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))

                if (isVodafoneCashMessage(body)) {
                    val parsed = parseSmsBody(body)
                    val normalized = normalizeEgyptianPhone(parsed.senderPhone ?: sender)
                    val requested = normalizeEgyptianPhone(task.senderPhoneRequested ?: "")
                    val amount = parsed.amount

                    val amountMatch = if (task.fingerprintAmount != null && amount != null) {
                        kotlin.math.abs(task.fingerprintAmount - amount) <= 0.01
                    } else false

                    val phoneMatch = requested.isNotEmpty() && normalized == requested

                    candidates.add(ScannedMessage(
                        sender = sender,
                        senderPhone = normalized,
                        senderName = parsed.senderName,
                        amount = amount,
                        transactionId = parsed.transactionId,
                        body = body,
                        date = date,
                        amountMatch = amountMatch,
                        phoneMatch = phoneMatch,
                        score = (if (amountMatch) 2 else 0) + (if (phoneMatch) 2 else 0)
                    ))
                }
            }
        }

        // Best match: score 4 = both amount and phone match
        val best = candidates.maxByOrNull { it.score }
        if (best != null && best.amountMatch && best.phoneMatch) {
            return ScanResult.Success(best)
        }

        // Amount mismatch: found a message from same sender but amount differs
        val phoneOnlyMatch = candidates.filter { it.phoneMatch && !it.amountMatch }
        if (phoneOnlyMatch.isNotEmpty()) {
            val closest = phoneOnlyMatch.maxByOrNull { it.score }!!
            return ScanResult.AmountMismatch(
                closest,
                expectedAmount = task.fingerprintAmount ?: task.amountRequested,
                foundAmount = closest.amount ?: 0.0
            )
        }

        if (candidates.isEmpty()) {
            return ScanResult.NotFound("لم يتم العثور على رسائل فودافون كاش في الجهاز")
        }

        return ScanResult.NotFound("تم العثور على ${candidates.size} رسالة لكن لا توجد مطابقة تامة")
    }

    private fun sendTaskResult(
        context: Context,
        task: Task,
        result: ScanResult,
        webhookUrl: String,
        secret: String,
        onSent: ((Boolean) -> Unit)? = null
    ) {
        val idempotencyKey = "${task.taskId}-${task.requestId}"
        val body = mutableMapOf<String, Any>(
            "action" to "task_result",
            "device_id" to HeartbeatManager.getDeviceId(context),
            "task_id" to task.taskId,
            "idempotency_key" to idempotencyKey,
            "status" to when (result) {
                is ScanResult.Success -> "success"
                is ScanResult.AmountMismatch -> "amount_mismatch"
                is ScanResult.NotFound -> "not_found"
                is ScanResult.Failure -> "failure"
            }
        )

        if (result is ScanResult.Success) {
            val m = result.message
            body["result_data"] = mapOf(
                "sender_phone" to (m.senderPhone ?: ""),
                "sender_name" to (m.senderName ?: ""),
                "amount" to (m.amount ?: 0.0),
                "transaction_id" to (m.transactionId ?: ""),
                "receiver_wallet" to (m.receiverWallet ?: ""),
                "sms_body" to m.body,
                "scanned_at" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
        }

        if (result is ScanResult.Failure) body["failure_reason"] = result.reason
        if (result is ScanResult.NotFound) body["failure_reason"] = result.reason
        if (result is ScanResult.AmountMismatch) body["failure_reason"] = "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"

        WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { success, msg, _ ->
            Log.d(TAG, "Task result sent: $success — $msg")
            // Notify local observer for UI update
            taskResultCallback?.invoke(task, result)
            onSent?.invoke(success)
        }
    }

    // Callback for UI updates when a task result is sent
    var taskResultCallback: ((task: Task, result: ScanResult) -> Unit)? = null

    private fun isVodafoneCashMessage(body: String): Boolean {
        return KEYWORDS.any { body.contains(it, ignoreCase = true) }
    }

    // Enhanced SMS parser — extracts transaction_id, receiver_wallet, sender_name
    /**
     * نسخة عامة من parseSmsBody للاختبار السريع دون قراءة صندوق الرسائل.
     */
    fun testParseSms(text: String): ParsedSms = parseSmsBody(text)

    private fun parseSmsBody(text: String): ParsedSms {
        // ── الأولوية: رسالة فودافون كاش المصرية الرسمية ──────────────────────
        // "تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل بإسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13. رقم العملية: 022655099780"
        val officialVFRegex = Regex(
            """تم\s+استلام\s+مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه\s*من\s*(\+?0?1[0-9]{9})\s*[:؛]?\s*المسجل\s+بإسم\s+([A-Za-z][A-Za-z0-9\s]{1,40})\s+على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})\s+بتاريخ\s+\d{1,2}:\d{2}\s+\d{2}-\d{2}-\d{2,4}.*?(?:رقم\s+العملية|رقم العملية)[:\s]+([A-Za-z0-9]+)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val om = officialVFRegex.find(text)
        if (om != null) {
            return ParsedSms(
                senderPhone  = om.groupValues[2].trim(),
                senderName   = om.groupValues[3].trim(),
                amount       = om.groupValues[1].replace(",", "").toDoubleOrNull(),
                transactionId= om.groupValues[5].trim(),
                body         = text,
                date         = System.currentTimeMillis(),
                receiverWallet = om.groupValues[4].trim()
            )
        }

        // ── fallback: باقي أنماط فودافون كاش ────────────────────────────────
        val amountRegexes = listOf(
            Regex("تم استلام مبلغ\\s*([\\d,]+\\.?\\d{0,2})\\s*جنيه"),
            Regex("مبلغ\\s*([\\d,]+\\.?\\d{0,2})\\s*جنيه"),
            Regex("استلمت\\s+(?:من\\s+.+?\\s+)?مبلغ\\s*([\\d,]+\\.?\\d{0,2})"),
            Regex("received\\s+(?:egp\\s+)?([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("egp\\s+([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("([\\d,]+\\.\\d{1,2})\\s*جنيه"),
            Regex("([\\d,]+\\.?\\d{0,2})")
        )
        var amount: Double? = null
        for (re in amountRegexes) {
            val m = re.find(text)
            if (m != null) {
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
                if (v != null && v > 0) { amount = v; break }
            }
        }

        // Phone — from SMS sender line e.g. "من 01152210028"
        val phoneRegexes = listOf(
            Regex("من\\s*(\\+?0?1[0-9]{9})"),
            Regex("from\\s*(\\+?\\d[\\d ]{8,14})", RegexOption.IGNORE_CASE),
            Regex("(\\+?20\\s*1\\d{9})"),
            Regex("(01[0-9]{9})")
        )
        var senderPhone: String? = null
        for (re in phoneRegexes) {
            val m = re.find(text)
            if (m != null) { senderPhone = m.groupValues[1].replace("\\s".toRegex(), ""); break }
        }

        // Sender name — "بإسم AHMED REDA على" or "المسجل بإسم"
        val nameRegexes = listOf(
            Regex("(?:المسجل\\s+)?بإسم\\s+([A-Za-z][A-Za-z0-9 ]{1,40})\\s*على"),
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

        // Receiver wallet — "على رقم محفظتك 01097273680"
        val walletRegexes = listOf(
            Regex("على رقم محفظتك\\s*(0?1[0-9]{9})"),
            Regex("wallet[:\\s]*(\\+?0?1[0-9]{9})", RegexOption.IGNORE_CASE)
        )
        var receiverWallet: String? = null
        for (re in walletRegexes) {
            val m = re.find(text)
            if (m != null) { receiverWallet = m.groupValues[1]; break }
        }

        // Transaction ID — "رقم العملية: 022655099780"
        val txRegexes = listOf(
            Regex("رقم العملية[:\\s]+([A-Za-z0-9]+)"),
            Regex("كود المعاملة[:\\s]+([A-Za-z0-9]+)"),
            Regex("transaction\\s*id[:\\s]+([A-Za-z0-9]+)", RegexOption.IGNORE_CASE),
            Regex("\\b([0-9]{9,20})\\b")
        )
        var transactionId: String? = null
        for (re in txRegexes) {
            val m = re.find(text)
            if (m != null) { transactionId = m.groupValues[1]; break }
        }

        return ParsedSms(senderPhone, senderName, amount, transactionId, text, 0, receiverWallet)
    }

    private fun normalizeEgyptianPhone(raw: String): String {
        val digits = raw.replace("\\D".toRegex(), "")
        return when {
            digits.length == 10 && digits.startsWith("1") -> digits
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") && digits[2] == '1' -> digits.substring(2)
            digits.length == 13 && digits.startsWith("20") && digits[3] == '1' -> digits.substring(3)
            else -> digits
        }
    }

    sealed class ScanResult {
        data class Success(val message: ScannedMessage) : ScanResult()
        data class AmountMismatch(val message: ScannedMessage, val expectedAmount: Double, val foundAmount: Double) : ScanResult()
        data class Failure(val reason: String) : ScanResult()
        data class NotFound(val reason: String) : ScanResult()
    }

    data class ScannedMessage(
        val sender: String,
        val senderPhone: String?,
        val senderName: String?,
        val amount: Double?,
        val transactionId: String?,
        val body: String,
        val date: Long,
        val amountMatch: Boolean,
        val phoneMatch: Boolean,
        val score: Int,
        val receiverWallet: String? = null
    )
}
