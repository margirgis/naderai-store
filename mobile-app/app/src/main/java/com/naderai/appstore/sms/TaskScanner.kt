package com.naderai.appstore.sms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * يحلل رسائل Vodafone Cash ويستخرج منها:
 * - المبلغ المحول
 * - رقم المرسل
 * - اسم المرسل
 * - رقم العملية
 * - محفظة المستلم
 */
object TaskScanner {

    private val TAG = "TaskScanner"

    // ── الشرط الذهبي: قائمة الأسماء والأرقام الرسمية لفودافون كاش مصر ──
    // أي SMS من غيرهم = غير رسمي ويُرفض فوراً
    private val OFFICIAL_SENDER_ADDRESSES = setOf(
        "vodafone", "vodafonecash", "vf-cash", "vfcash",
        "vf cash", "vc", "voda", "vodafone cash",
        "2010", "2020", "2880", "16888", "888"
    )

    private val AMOUNT_PATTERNS = listOf(
        Regex("""تم استلام\s+([\d,]+(?:\.\d{1,2})?)\s*(?:جنيه|EGP|ج\.م)"""),
        Regex("""received\s+([\d,]+(?:\.\d{1,2})?)\s*(?:EGP|egp)"""),
        Regex("""([\d,]+(?:\.\d{1,2})?)\s*(?:جنيه|EGP|ج\.م)\s*من"""),
        Regex("""charged\s+([\d,]+(?:\.\d{1,2})?)\s*EGP"""),
        Regex("""استلمت\s+([\d,]+(?:\.\d{1,2})?)\s*(?:جنيه|EGP)"""),
        Regex("""تحويل مبلغ\s+([\d,]+(?:\.\d{1,2})?)"""),
        Regex("""([\d,]+(?:\.\d{1,2})?)\s*EGP"""),
    )

    private val SENDER_PHONE_PATTERNS = listOf(
        Regex("""من\s+(\+?01\d{9})"""),
        Regex("""from\s+(\+?01\d{9})"""),
        Regex("""(\+?01\d{9})\s+إلى"""),
        Regex("""(\+?01\d{9})"""),
    )

    private val SENDER_NAME_PATTERNS = listOf(
        Regex("""المسجل\s+بإسم\s+([^\n]{2,60}?)\s+على"""),
        Regex("""بإسم\s+([^\n]{2,60}?)\s+على"""),
        Regex("""من\s+([^0-9\n]{2,40}?)\s+(?:رقم|إلى|بتاريخ|\()"""),
        Regex("""from\s+([A-Za-z\s]{2,40}?)\s+(?:number|to|on)"""),
    )

    private val TX_ID_PATTERNS = listOf(
        Regex("""رقم العملية[:\s]+(\d{6,})"""),
        Regex("""Transaction ID[:\s]+([A-Za-z0-9-]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""Ref[.:\s]+([A-Za-z0-9-]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""رقم المرجع[:\s]+([A-Za-z0-9-]{6,})"""),
    )

    private val RECEIVER_PATTERNS = listOf(
        Regex("""على رقم محفظتك\s+(\+?01\d{9})"""),
        Regex("""على\s+(\+?01\d{9})"""),
        Regex("""إلى\s+(\+?01\d{9})"""),
        Regex("""to\s+(\+?01\d{9})"""),
    )

    data class ParsedSms(
        val amount: Double?,
        val senderPhone: String?,
        val senderName: String?,
        val transactionId: String?,
        val receiverWallet: String?,
        val rawText: String
    )

    data class Task(
        val taskId: String,
        val requestId: String,
        val amountRequested: Double,
        val senderPhoneRequested: String?,
        val fingerprintAmount: Double?,
        val senderNameRequested: String? = null,
        val receiverWalletRequested: String? = null,
        val transactionIdExpected: String? = null,
    )

    fun isOfficialVodafoneCashMessage(body: String): Boolean {
        val lower = body.lowercase()
        // لازم يحتوي على "استلام" أو "received" — مش مجرد "فودافون"
        val hasReceived = lower.contains("تم استلام") || lower.contains("استلام") ||
                          lower.contains("استلمت") || lower.contains("received")
        if (!hasReceived) return false
        // لازم يحتوي على مؤشر فودافون في النص
        val hasVF = lower.contains("vodafone") || lower.contains("فودافون") ||
                    lower.contains("voda") || lower.contains("محفظتك")
        if (!hasVF) return false
        // رفض رسائل الصادرة بفحص البادئة
        val prefix = body.trimStart().take(20).lowercase()
        val outgoing = listOf("تم تحويل", "تم سحب", "قمت بتحويل", "you have sent", "you transferred")
        if (outgoing.any { prefix.contains(it) }) return false
        return true
    }

    /** يتحقق أن الـ ADDRESS هو فودافون الرسمي — الحاجز الأساسي قبل قراءة النص */
    fun isOfficialVodafoneSender(smsAddress: String): Boolean {
        val lower = smsAddress.trim().lowercase()
        return OFFICIAL_SENDER_ADDRESSES.any { lower.contains(it) }
    }

    fun testParseSms(body: String): ParsedSms = parseSms(body)

    fun scanInboxForTest(context: Context, limit: Int = 50): List<ParsedSms> {
        val results = mutableListOf<ParsedSms>()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                null, null,
                "date DESC LIMIT $limit"
            )
            cursor?.use { c ->
                val bodyIdx = c.getColumnIndex("body")
                val addrIdx = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    val body = c.getString(bodyIdx) ?: continue
                    val addr = c.getString(addrIdx) ?: ""
                    // ── SECURITY: فحص الـ ADDRESS أولاً — لازم من فودافون الرسمي ──
                    if (!isOfficialVodafoneSender(addr)) continue
                    if (isOfficialVodafoneCashMessage(body)) {
                        val parsed = parseSms(body)
                        if (parsed.amount != null) results.add(parsed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanInboxForTest error: ${e.message}", e)
        }
        return results
    }

    /**
     * يطابق رسالة مع المهمة باستخدام المبلغ الدقيق، ثم رقم المرسل إن توفر،
     * ويمنع القبول إذا كان المبلغ غير مطابق أو بيانات المطابقة الأساسية غير متوافقة.
     * لا يوجد أي tolerance بالقروش؛ المقارنة تتم بوحدات القرش لتجنب أخطاء Double.
     */
    fun matchSmsToTask(smsBody: String, task: Task): Boolean {
        if (!isOfficialVodafoneCashMessage(smsBody)) return false
        val parsed = parseSms(smsBody)
        val amount = parsed.amount ?: return false
        val target = task.fingerprintAmount ?: task.amountRequested

        val smsCents = toCents(amount) ?: return false
        val targetCents = toCents(target) ?: return false
        if (smsCents != targetCents) {
            Log.d(TAG, "Reject amount mismatch: sms=${smsCents}c task=${targetCents}c")
            return false
        }

        val expectedPhone = normalizePhone(task.senderPhoneRequested)
        val actualPhone = normalizePhone(parsed.senderPhone)
        if (!expectedPhone.isNullOrEmpty()) {
            if (actualPhone.isNullOrEmpty()) {
                Log.d(TAG, "Reject missing sender phone for task ${task.taskId}")
                return false
            }
            if (expectedPhone != actualPhone) {
                Log.d(TAG, "Reject sender phone mismatch for task ${task.taskId}")
                return false
            }
        }

        val expectedReceiver = normalizePhone(task.receiverWalletRequested)
        if (!expectedReceiver.isNullOrEmpty()) {
            val actualReceiver = normalizePhone(parsed.receiverWallet)
            if (actualReceiver.isNullOrEmpty() || expectedReceiver != actualReceiver) {
                Log.d(TAG, "Reject receiver wallet mismatch for task ${task.taskId}")
                return false
            }
        }

        if (!task.transactionIdExpected.isNullOrBlank()) {
            if (parsed.transactionId.isNullOrBlank() || parsed.transactionId != task.transactionIdExpected) {
                Log.d(TAG, "Reject transaction ID mismatch for task ${task.taskId}")
                return false
            }
        }

        return true
    }

    fun buildResultPayload(
        taskId: String,
        smsBody: String,
        matched: Boolean,
        failureReason: String? = null
    ): Map<String, Any> {
        val parsed = parseSms(smsBody)
        return buildMap {
            put("action", "sms_scan_result")
            put("task_id", taskId)
            put("matched", matched)
            put("sms_body", smsBody)
            parsed.amount?.let { put("confirmed_amount", it) }
            parsed.senderPhone?.let { put("sender_phone", it) }
            parsed.senderName?.let { put("sender_name", it) }
            parsed.transactionId?.let { put("transaction_id", it) }
            parsed.receiverWallet?.let { put("receiver_wallet", it) }
            if (!matched) put("failure_reason", failureReason ?: "لم يتم العثور على تطابق")
        }
    }

    private fun parseSms(body: String): ParsedSms {
        val amount = extractAmount(body)
        val senderPhone = extractFirst(SENDER_PHONE_PATTERNS, body)
        val senderName = extractFirst(SENDER_NAME_PATTERNS, body)?.trim()?.replace(Regex("\\s+"), " ")
        val transactionId = extractFirst(TX_ID_PATTERNS, body)
        val receiverWallet = extractFirst(RECEIVER_PATTERNS, body)
        return ParsedSms(amount, senderPhone, senderName, transactionId, receiverWallet, body)
    }

    private fun extractAmount(body: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(body) ?: continue
            val raw = match.groupValues[1].replace(",", "")
            return raw.toDoubleOrNull()
        }
        return null
    }

    private fun extractFirst(patterns: List<Regex>, body: String): String? {
        for (p in patterns) {
            val m = p.find(body) ?: continue
            if (m.groupValues.size > 1) return m.groupValues[1].trim()
        }
        return null
    }

    private fun toCents(value: Double): Long? {
        if (!value.isFinite() || value < 0) return null
        return kotlin.math.round(value * 100.0).toLong()
    }

    private fun normalizePhone(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter(Char::isDigit)
        return when {
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") -> digits.substring(2)
            digits.length == 10 && digits.startsWith("1") -> digits
            else -> digits.ifBlank { null }
        }
    }

    private fun isRelevantSender(address: String): Boolean {
        val lower = address.lowercase()
        return OFFICIAL_SENDER_ADDRESSES.any { lower.contains(it) }
    }
}
