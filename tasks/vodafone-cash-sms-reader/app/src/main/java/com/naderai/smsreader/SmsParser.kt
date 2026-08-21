package com.naderai.smsreader

import android.util.Log

/**
 * Parser موحّد لرسائل Vodafone Cash الرسمية المصرية — Phase-2.
 *
 * مبادئ:
 * - Parser واحد فقط (لا يوجد parser ثانٍ في المشروع).
 * - يُرجع ParseResult مع reason واضح عند الفشل.
 * - يستقبل originatingAddress من Android SMS provider (لا يخترعه).
 * - يستخرج مبلغ الاستلام فقط، لا يخلطه برصيد المحفظة.
 * - يدعم "باسم" و"بإسم" وأسماء عربية وإنجليزية كاملة.
 */
object SmsParser {

    private const val TAG = "SmsParser"

    /**
     * Regex الرئيسية للصيغة الرسمية الحديثة (متعددة الأسطر).
     * مثال حقيقي:
     *   تم استلام مبلغ 400 جنيه من رقم 01030951228 المسجل بإسم Wessam A Ahmed Ali
     *   على رقم محفظتك 01097273680.
     *   رصيدك الحالي: 84324.60 جنيه
     *   تاريخ العملية: 00:15 26-08-21
     *   رقم العملية: 022896233255
     */
    private val OFFICIAL_VF_REGEX = Regex(
        """تم\s+استلام\s+مبلغ\s*([\d,٫]+\.?\d{0,2})\s*جنيه\s*من\s*(?:رقم\s*)?(\+?0?1[0-9]{9})""" +
        """(?:\s*[؛;.,]?\s*)""" +
        """المسجل\s+(?:باسم|بإسم)\s+""" +
        """([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,80}?)""" +
        """\s+على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // صيغة بديلة قديمة (سطر واحد بفاصلة منقوطة):
    // تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل باسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13
    private val LEGACY_VF_REGEX = Regex(
        """تم\s+استلام\s+مبلغ\s*([\d,٫]+\.?\d{0,2})\s*جنيه\s*من\s*(\+?0?1[0-9]{9})[؛;,\s]+""" +
        """المسجل\s+(?:باسم|بإسم)\s+([\u0600-\u06FFA-Za-z][\u0600-\u06FFA-Za-z0-9 ]{0,80}?)\s+على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TX_ID_REGEX     = Regex("""رقم\s+العملية[:\s]+([0-9]{9,20})""")
    // يدعم "تاريخ العملية: 00:15 26-08-21" و"بتاريخ 15:54 26-08-13"
    private val DATE_REGEX_NEW  = Regex("""(?:تاريخ\s+العملية|بتاريخ)[:\s]+(\d{1,2}:\d{2}\s+\d{1,2}-\d{1,2}-\d{2,4})""")
    private val DATE_REGEX_OLD  = Regex("""بتاريخ\s+(\d{1,2}:\d{2}\s+\d{1,2}-\d{1,2}-\d{2,4})""")

    // ── Source validation ────────────────────────────────────────────────

    /**
     * قائمة الـ originating addresses الرسمية لفودافون كاش مصر.
     * يُقرأ الـ ADDRESS من Android SMS provider — لا يُختَرع.
     *
     * القيم المعروفة على الأجهزة المصرية:
     *   - "vodafone", "vodafonecash", "vf-cash", "vfcash", "vf cash", "vodafone cash"
     *   - "2010", "2020", "2880", "16888", "888" (أرقام الخدمة الرسمية)
     */
    val OFFICIAL_SENDER_ADDRESSES: Set<String> = setOf(
        "vodafone", "vodafonecash", "vf-cash", "vfcash",
        "vf cash", "vc", "voda", "vodafone cash",
        "2010", "2020", "2880", "16888", "888"
    )

    /**
     * يتحقق أن originating address ينتمي لفودافون الرسمي.
     * الفحص: تطابق جزئي case-insensitive.
     * أي رقم عادي (11 رقم) لا يتطابق مع القائمة = رفض.
     */
    fun isOfficialVodafoneSender(address: String): Boolean {
        val lower = address.trim().lowercase()
        return OFFICIAL_SENDER_ADDRESSES.any { lower.contains(it) }
    }

    /**
     * يتحقق أن الرسالة صيغة استلام رسمية (ليست صادرة أو إشعار آخر).
     * يُستخدم كـ quick-filter قبل parse الكامل.
     */
    fun isOfficialReceivedMessage(body: String): Boolean {
        val mandatory = listOf("فودافون", "Vodafone", "محفظتك")
        val received  = listOf("تم استلام", "استلام", "استلمت", "received")
        if (mandatory.none { body.contains(it, ignoreCase = true) }) return false
        if (received.none  { body.contains(it, ignoreCase = true) }) return false
        val prefix = body.trimStart().take(20)
        val outgoing = listOf("تم تحويل", "تحويل", "تم سحب", "سحب", "تم دفع", "دفع",
                              "you have sent", "you transferred")
        if (outgoing.any { prefix.contains(it, ignoreCase = true) }) return false
        val hasWalletPattern = body.contains("محفظتك") || body.contains("رقم العملية")
        return hasWalletPattern
    }

    // ── Parser الموحّد الرئيسي (Phase-2) ────────────────────────────────

    /**
     * يُحلّل رسالة Vodafone Cash ويُرجع ParseResult مع reason واضح عند الفشل.
     *
     * @param body               نص الرسالة
     * @param originatingAddress الـ ADDRESS من Android SMS provider (مطلوب للتحقق من المصدر)
     * @param smsDateMs          وقت الرسالة من Android SMS provider (Telephony.Sms.DATE)
     */
    fun parse(body: String, originatingAddress: String, smsDateMs: Long): ParseResult {
        val raw = body
        val normalized = body
            .replace(Regex("[٫،,]"), ".")
            .replace(Regex("[\u200B-\u200D\uFEFF]"), "") // إزالة zero-width chars

        // 1. تحقق المصدر أولاً — الحاجز الأساسي
        if (!isOfficialVodafoneSender(originatingAddress)) {
            Log.w(TAG, "PARSE_FAIL | reason=invalid_sender_address address=$originatingAddress")
            return ParseResult(
                success = false,
                amount = null, senderPhone = null, senderName = null,
                receiverWallet = null, smsTimestamp = smsDateMs,
                transactionId = null, originatingAddress = originatingAddress,
                rawMessage = raw,
                reason = ParseResult.REASON_INVALID_SENDER_ADDR
            )
        }

        // 2. حاول الأنماط بالترتيب
        val regexMatch = OFFICIAL_VF_REGEX.find(normalized) ?: LEGACY_VF_REGEX.find(normalized)

        if (regexMatch == null) {
            Log.w(TAG, "PARSE_FAIL | reason=unsupported_format body_prefix=${body.take(60)}")
            return ParseResult(
                success = false,
                amount = null, senderPhone = null, senderName = null,
                receiverWallet = null, smsTimestamp = smsDateMs,
                transactionId = null, originatingAddress = originatingAddress,
                rawMessage = raw,
                reason = ParseResult.REASON_UNSUPPORTED_FORMAT
            )
        }

        // 3. استخرج الحقول
        val amountRaw = regexMatch.groupValues[1].replace(",", "").replace("٫", ".")
        val amount = amountRaw.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Log.w(TAG, "PARSE_FAIL | reason=missing_amount raw=$amountRaw")
            return ParseResult(
                success = false,
                amount = null, senderPhone = null, senderName = null,
                receiverWallet = null, smsTimestamp = smsDateMs,
                transactionId = null, originatingAddress = originatingAddress,
                rawMessage = raw,
                reason = ParseResult.REASON_MISSING_AMOUNT
            )
        }

        val senderPhone    = normalizeEgyptianPhone(regexMatch.groupValues[2])
        val senderName     = regexMatch.groupValues[3].trim().takeIf { it.isNotEmpty() }
        val receiverWallet = normalizeEgyptianPhone(regexMatch.groupValues[4])

        if (receiverWallet.isEmpty()) {
            Log.w(TAG, "PARSE_FAIL | reason=invalid_wallet")
            return ParseResult(
                success = false,
                amount = amount, senderPhone = senderPhone, senderName = senderName,
                receiverWallet = null, smsTimestamp = smsDateMs,
                transactionId = null, originatingAddress = originatingAddress,
                rawMessage = raw,
                reason = ParseResult.REASON_INVALID_WALLET
            )
        }

        // 4. رقم العملية (transaction_id) — مطلوب للـ duplicate protection
        val txId = TX_ID_REGEX.find(normalized)?.groupValues?.get(1)?.trim()
        if (txId.isNullOrEmpty()) {
            Log.w(TAG, "PARSE_FAIL | reason=missing_transaction_id amount=$amount phone=$senderPhone")
            return ParseResult(
                success = false,
                amount = amount, senderPhone = senderPhone, senderName = senderName,
                receiverWallet = receiverWallet, smsTimestamp = smsDateMs,
                transactionId = null, originatingAddress = originatingAddress,
                rawMessage = raw,
                reason = ParseResult.REASON_MISSING_TX_ID
            )
        }

        // 5. وقت الرسالة — من الـ provider أولاً، وإلا من نص الرسالة
        val parsedDate = parseDate(
            DATE_REGEX_NEW.find(normalized)?.groupValues?.get(1)
                ?: DATE_REGEX_OLD.find(normalized)?.groupValues?.get(1)
        )
        // نستخدم provider date كمصدر رئيسي؛ date من النص كـ sanity check فقط
        val finalTimestamp = if (smsDateMs > 0L) smsDateMs else parsedDate

        Log.d(TAG, "PARSE_OK | amount=$amount phone=$senderPhone name=$senderName wallet=$receiverWallet tx=$txId ts=$finalTimestamp addr=$originatingAddress")
        return ParseResult(
            success = true,
            amount = amount,
            senderPhone = senderPhone,
            senderName = senderName,
            receiverWallet = receiverWallet,
            smsTimestamp = finalTimestamp,
            transactionId = txId,
            originatingAddress = originatingAddress,
            rawMessage = raw,
            reason = null
        )
    }

    // ── Adapter للكود القديم (backward-compat) ──────────────────────────

    /**
     * Adapter قديم — يُستخدم فقط في SmsReceiver (LocalSmsQueue) حيث لا يوجد originatingAddress.
     * يُرجع ParsedSms للتوافق مع LocalSmsQueue.QueuedSms.
     * الكود الجديد يستخدم parse() مباشرة.
     */
    fun parseReceived(body: String): ParsedSms {
        // نمرر "vodafone" كـ address افتراضي هنا لأن SmsReceiver يتحقق من isOfficialReceivedMessage أولاً
        val result = parse(body, originatingAddress = "vodafone", smsDateMs = System.currentTimeMillis())
        return ParsedSms(
            senderPhone    = result.senderPhone,
            senderName     = result.senderName,
            amount         = result.amount,
            transactionId  = result.transactionId,
            body           = body,
            date           = result.smsTimestamp,
            receiverWallet = result.receiverWallet
        )
    }

    // ── Utilities ────────────────────────────────────────────────────────

    fun normalizeEgyptianPhone(raw: String): String {
        val digits = raw.replace(Regex("""\D"""), "")
        return when {
            digits.length == 10 && digits.startsWith("1")  -> digits
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") && digits[2] == '1' -> digits.substring(2)
            digits.length == 13 && digits.startsWith("201")  -> digits.substring(3)
            digits.length == 13 && digits.startsWith("+20") && digits[3] == '1' -> digits.substring(3)
            else -> digits
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return System.currentTimeMillis()
        val formats = listOf(
            "HH:mm dd-MM-yy",
            "HH:mm dd-MM-yyyy",
            "hh:mm a dd-MM-yy"
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo")
                val parsed = sdf.parse(dateStr.trim())
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        Log.w(TAG, "PARSE_DATE_FAIL | raw=$dateStr")
        return System.currentTimeMillis()
    }
}
