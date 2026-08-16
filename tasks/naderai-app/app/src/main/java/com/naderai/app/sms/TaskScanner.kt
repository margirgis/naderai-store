package com.naderai.app.sms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * يحلل رسائل Vodafone Cash ويستخرج منها:
 * - المبلغ المحول
 * - رقم ورقم المرسل
 * - اسم المرسل
 * - رقم العملية
 * - محفظة المستلم
 *
 * يدعم أيضاً:
 * - اختبار تحليل رسالة واحدة (testParseSms)
 * - فحص صندوق الرسائل الوارد (scanInboxForTest)
 */
object TaskScanner {

    private val TAG = "TaskScanner"

    // أنماط رسائل Vodafone Cash الرسمية
    private val VODAFONE_NUMBERS = setOf(
        "VOD-CASH", "VodafoneCash", "Vodafone Cash",
        "Vodafone", "01010", "01011", "2olo",
        "VODAFONE", "vodafonecash"
    )

    // نمط استخراج المبلغ (مثال: 5.06 EGP أو 5.06 جنيه)
    private val AMOUNT_PATTERNS = listOf(
        Regex("""تم استلام\s+([\d,]+(?:\.\d+)?)\s*(?:جنيه|EGP|ج\.م)"""),
        Regex("""received\s+([\d,]+(?:\.\d+)?)\s*(?:EGP|egp)"""),
        Regex("""([\d,]+(?:\.\d+)?)\s*(?:جنيه|EGP|ج\.م)\s*من"""),
        Regex("""charged\s+([\d,]+(?:\.\d+)?)\s*EGP"""),
        Regex("""استلمت\s+([\d,]+(?:\.\d+)?)\s*(?:جنيه|EGP)"""),
        Regex("""تحويل مبلغ\s+([\d,]+(?:\.\d+)?)"""),
        Regex("""([\d,]+(?:\.\d+)?)\s*EGP"""),
    )

    // نمط استخراج رقم المرسل
    private val SENDER_PHONE_PATTERNS = listOf(
        Regex("""من\s+(\+?01\d{9})"""),
        Regex("""from\s+(\+?01\d{9})"""),
        Regex("""(\+?01\d{9})\s+إلى"""),
        Regex("""(\+?01\d{9})"""),
    )

    // نمط استخراج اسم المرسل
    private val SENDER_NAME_PATTERNS = listOf(
        Regex("""من\s+([^0-9\n]{2,40}?)\s+(?:رقم|إلى|بتاريخ|\()"""),
        Regex("""from\s+([A-Za-z\s]{2,40}?)\s+(?:number|to|on)"""),
        Regex("""من\s+([^\d\n]{2,30})"""),
    )

    // نمط رقم العملية
    private val TX_ID_PATTERNS = listOf(
        Regex("""رقم العملية[:\s]+(\d+)"""),
        Regex("""Transaction ID[:\s]+(\d+)"""),
        Regex("""Ref[.:\s]+(\d+)"""),
        Regex("""رقم المرجع[:\s]+(\d+)"""),
    )

    // نمط محفظة المستلم
    private val RECEIVER_PATTERNS = listOf(
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
        val fingerprintAmount: Double?
    )

    /** هل هذه رسالة فودافون كاش رسمية؟ */
    fun isOfficialVodafoneCashMessage(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("vodafone") || lower.contains("فودافون") ||
            lower.contains("voda") || lower.contains("تم استلام") ||
            lower.contains("محفظة") || lower.contains("تحويل مبلغ")
    }

    /** تحليل رسالة واحدة واستخراج البيانات */
    fun testParseSms(body: String): ParsedSms = parseSms(body)

    /** فحص صندوق الوارد وإرجاع رسائل Vodafone Cash */
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
                    if (isRelevantSender(addr) || isOfficialVodafoneCashMessage(body)) {
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
     * يفحص SMS الوارد ويطابقه مع مهمة من السيرفر.
     * يُعيد true إذا كان المبلغ يتطابق ضمن هامش 0.50 جنيه.
     */
    fun matchSmsToTask(smsBody: String, task: Task): Boolean {
        if (!isOfficialVodafoneCashMessage(smsBody)) return false
        val parsed = parseSms(smsBody)
        val amount = parsed.amount ?: return false
        val target = task.fingerprintAmount ?: task.amountRequested
        val diff = Math.abs(amount - target)
        Log.d(TAG, "Match check: sms=${amount}, task=${target}, diff=${diff}")
        return diff <= 0.50
    }

    /** يُنشئ payload لإرسال نتيجة الفحص للسيرفر */
    fun buildResultPayload(
        taskId: String,
        smsBody: String,
        matched: Boolean,
        failureReason: String? = null
    ): Map<String, Any> {
        val parsed = if (matched) parseSms(smsBody) else ParsedSms(null, null, null, null, null, smsBody)
        return buildMap {
            put("action", "sms_scan_result")
            put("task_id", taskId)
            put("matched", matched)
            put("sms_body", smsBody)
            if (matched) {
                parsed.amount?.let { put("confirmed_amount", it) }
                parsed.senderPhone?.let { put("sender_phone", it) }
                parsed.senderName?.let { put("sender_name", it) }
                parsed.transactionId?.let { put("transaction_id", it) }
            } else {
                put("failure_reason", failureReason ?: "لم يتم العثور على تطابق")
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun parseSms(body: String): ParsedSms {
        val amount = extractAmount(body)
        val senderPhone = extractFirst(SENDER_PHONE_PATTERNS, body)
        val senderName = extractFirst(SENDER_NAME_PATTERNS, body)?.trim()
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

    private fun isRelevantSender(address: String): Boolean {
        val lower = address.lowercase()
        return VODAFONE_NUMBERS.any { lower.contains(it.lowercase()) }
    }
}
