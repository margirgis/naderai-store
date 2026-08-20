package com.naderai.smsreader

import android.util.Log

/**
 * Parser موحّد لرسائل Vodafone Cash الرسمية المصرية.
 * يستخرج مبلغ **الاستلام** فقط، ولا يخلط بينه وبين رصيد المحفظة الحالي.
 */
object SmsParser {

    private const val TAG = "SmsParser"

    private const val PHONE_PATTERN = """(?:\+?0?1|0?1)?[0-9]{9}"""

    /**
     * Regex رئيسية للرسالة الرسمية الحديثة (متعددة الأسطر):
     * "تم استلام مبلغ 5.18 جنيه من رقم 01012345678 المسجل باسم محمد أحمد على رقم محفظتك 01098765432.
     *  رصيدك الحالي: 606.82 جنيه.
     *  رقم العملية: 022768543034"
     *
     * والنسخة القديمة (سطر واحد):
     * "تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل باسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13. رقم العملية: 022655099780"
     */
    private val OFFICIAL_VF_REGEX = Regex(
        """تم\s+استلام\s+مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه\s*من\s*(?:رقم\s*)?(\+?0?1[0-9]{9})""" +
        """(?:\s*[؛;.,]?\s*)""" +
        """المسجل\s+باسم\s+""" +
        """([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,60}?)""" +
        """\s+على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TX_ID_REGEX = Regex("""رقم\s+العملية[:\s]+([0-9]{9,20})""")
    private val DATE_REGEX = Regex("""بتاريخ\s+(\d{1,2}:\d{2}\s+\d{1,2}-\d{1,2}-\d{2,4})""")

    /**
     * يتحقق أن الرسالة رسالة استلام رسمية من فودافون كاش.
     * نرفض رسائل "تم تحويل" (الصادرة) بفحص البادئة فقط.
     */
    fun isOfficialReceivedMessage(body: String): Boolean {
        val mandatory = listOf("فودافون", "Vodafone", "فودافون كاش", "Vodafone Cash", "محفظتك")
        val received  = listOf("تم استلام", "استلام", "استلمت", "received")
        if (mandatory.none { body.contains(it, ignoreCase = true) }) return false
        if (received.none  { body.contains(it, ignoreCase = true) }) return false

        // رفض رسائل الصادرة — فحص أول 20 حرف فقط
        val prefix = body.trimStart().take(20)
        val outgoing = listOf("تم تحويل", "تحويل", "تم سحب", "سحب", "تم دفع", "دفع",
                              "you have sent", "you transferred")
        if (outgoing.any { prefix.contains(it, ignoreCase = true) }) return false

        // لازم يحتوي على نمط محفظة (رقم محفظتك) أو رقم العملية — دليل أنها رسمية
        val hasWalletPattern = body.contains("محفظتك") || body.contains("رقم العملية")
        if (!hasWalletPattern) return false

        return true
    }

    /**
     * يستخرج بيانات التحويل الوارد من SMS.
     * يعيد null للمبلغ إذا لم يعثر على عبارة "استلام".
     */
    fun parseReceived(body: String): ParsedSms {
        val normalized = body.replace(Regex("[٫،]"), ".") // convert Arabic/ Persian separators

        // أولوية قصوى: النمط الرسمي
        val official = OFFICIAL_VF_REGEX.find(normalized)
        if (official != null) {
            val amount = official.groupValues[1].replace(",", "").toDoubleOrNull()
            val senderPhone = normalizeEgyptianPhone(official.groupValues[2])
            val senderName = official.groupValues[3].trim().takeIf { it.isNotEmpty() }
            val receiverWallet = normalizeEgyptianPhone(official.groupValues[4])
            val txId = TX_ID_REGEX.find(normalized)?.groupValues?.get(1)?.trim()
            val date = parseDate(DATE_REGEX.find(normalized)?.groupValues?.get(1))
            Log.d(TAG, "Official parse: amount=$amount, phone=$senderPhone, name=$senderName, wallet=$receiverWallet, tx=$txId")
            return ParsedSms(
                senderPhone = senderPhone,
                senderName = senderName,
                amount = amount,
                transactionId = txId,
                body = body,
                date = date,
                receiverWallet = receiverWallet
            )
        }

        // Fallback: نبحث عن "تم استلام مبلغ X" أو "استلمت X" فقط — لا نقرأ "رصيدك الحالي"
        val amount = extractReceivedAmount(normalized)
        val senderPhone = extractPhone(normalized)
        val senderName = extractName(normalized)
        val receiverWallet = extractReceiverWallet(normalized)
        val txId = extractTransactionId(normalized)
        val date = parseDate(DATE_REGEX.find(normalized)?.groupValues?.get(1))

        return ParsedSms(
            senderPhone = senderPhone,
            senderName = senderName,
            amount = amount,
            transactionId = txId,
            body = body,
            date = date,
            receiverWallet = receiverWallet
        )
    }

    /**
     * نستخرج المبلغ من عبارة "استلام" فقط. إذا لم نجدها، نرفض أي رقم آخر.
     */
    private fun extractReceivedAmount(body: String): Double? {
        val receivedAmountRegex = listOf(
            Regex("""تم\s+استلام\s+مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه"""),
            Regex("""استلام\s+مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه"""),
            Regex("""استلمت\s+(?:من\s+.*?\s+)?مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه?"""),
            Regex("""received\s+(?:amount\s+)?([\d,]+\.?\d{0,2})\s*(?:egp|جنيه)""", RegexOption.IGNORE_CASE)
        )
        for (re in receivedAmountRegex) {
            val m = re.find(body) ?: continue
            val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
            if (v != null && v > 0) return v
        }
        return null
    }

    private fun extractPhone(body: String): String? {
        val regexes = listOf(
            Regex("""من\s+رقم\s*(\+?0?1[0-9]{9})"""),
            Regex("""من\s*(\+?0?1[0-9]{9})"""),
            Regex("""from\s*(\+?0?1[0-9]{9})""", RegexOption.IGNORE_CASE)
        )
        for (re in regexes) {
            val m = re.find(body) ?: continue
            return normalizeEgyptianPhone(m.groupValues[1])
        }
        return null
    }

    private fun extractName(body: String): String? {
        val regexes = listOf(
            Regex("""المسجل\s+باسم\s+([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,60})\s*على"""),
            Regex("""باسم\s+([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,60})\s*على"""),
            Regex("""بإسم\s+([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,60})\s*على""")
        )
        for (re in regexes) {
            val m = re.find(body) ?: continue
            val candidate = m.groupValues[1].trim()
            if (candidate.isNotEmpty() && !candidate.matches(Regex("""\d+"""))) return candidate
        }
        return null
    }

    private fun extractReceiverWallet(body: String): String? {
        val re = Regex("""على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})""")
        return re.find(body)?.groupValues?.get(1)?.let { normalizeEgyptianPhone(it) }
    }

    private fun extractTransactionId(body: String): String? {
        val re = Regex("""رقم\s+العملية[:\s]+([0-9]{9,20})""")
        return re.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return System.currentTimeMillis()
        return try {
            val fmt = java.text.SimpleDateFormat("HH:mm dd-MM-yy", java.util.Locale.getDefault())
            fmt.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    fun normalizeEgyptianPhone(raw: String): String {
        val digits = raw.replace(Regex("""\D"""), "")
        return when {
            digits.length == 10 && digits.startsWith("1") -> digits
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") && digits[2] == '1' -> digits.substring(2)
            digits.length == 13 && digits.startsWith("20") && digits[3] == '1' -> digits.substring(3)
            digits.length == 13 && digits.startsWith("+20") && digits[3] == '1' -> digits.substring(3)
            else -> digits
        }
    }
}
