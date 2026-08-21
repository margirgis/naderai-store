package com.naderai.smsreader

/**
 * بيانات رسالة Vodafone Cash المُستخرَجة من SMS.
 * مستخدمة من SmsParser و TaskScanner.
 * Phase-1 (legacy internal) — استخدم ParseResult للواجهة الخارجية.
 */
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
 * Phase-2: نتيجة Parser الموحّد — مصدر الحقيقة الوحيد لكل استخراج SMS.
 *
 * عند success=true: كل الحقول المطلوبة موجودة.
 * عند success=false: reason يشرح سبب الفشل بدقة (بدون "Manual Review" عشوائي).
 *
 * أسباب الفشل القياسية:
 *   missing_transaction_id — رقم العملية غير موجود
 *   missing_amount         — المبلغ غير موجود أو صفر
 *   invalid_sender_address — المُرسِل ليس فودافون الرسمي
 *   invalid_wallet         — رقم المحفظة المستهدفة غير موجود
 *   timestamp_parse_failed — تعذّر تحليل وقت الرسالة
 *   unsupported_format     — النمط لا يطابق أي صيغة معروفة
 */
data class ParseResult(
    val success: Boolean,
    // حقول الاستخراج — كلها null عند success=false
    val amount: Double?,
    val senderPhone: String?,
    val senderName: String?,
    val receiverWallet: String?,
    val smsTimestamp: Long,           // System.currentTimeMillis() كـ fallback
    val transactionId: String?,
    val originatingAddress: String?,  // الـ ADDRESS الحقيقي من Android SMS provider
    val rawMessage: String,
    // سبب الفشل — غير null عند success=false فقط
    val reason: String? = null
) {
    companion object {
        // أسباب الفشل القياسية — ثوابت لمنع الأخطاء المطبعية
        const val REASON_MISSING_TX_ID       = "missing_transaction_id"
        const val REASON_MISSING_AMOUNT      = "missing_amount"
        const val REASON_INVALID_SENDER_ADDR = "invalid_sender_address"
        const val REASON_INVALID_WALLET      = "invalid_wallet"
        const val REASON_TIMESTAMP_FAILED    = "timestamp_parse_failed"
        const val REASON_UNSUPPORTED_FORMAT  = "unsupported_format"
    }
}

/**
 * Phase-2: نتيجة مطابقة SMS بالطلب — يفصل بيانات Order عن بيانات SMS.
 *
 * order.* = بيانات الطلب من السيرفر (لا تُعدَّل).
 * sms.*   = ما استُخرج من رسالة SMS.
 * match.* = نتيجة المقارنة بين الاثنين.
 */
data class MatchResult(
    // ── بيانات الطلب (Order) — من السيرفر ──────────────────────────────
    val orderExpectedAmount: Double,
    val orderSenderPhone: String?,
    val orderReceiverWallet: String?,
    val orderRequestId: String,
    // ── بيانات SMS المُستخرَجة ──────────────────────────────────────────
    val smsAmount: Double?,
    val smsSenderPhone: String?,
    val smsSenderName: String?,
    val smsTransactionId: String?,
    val smsTimestamp: Long,
    val smsReceiverWallet: String?,
    val smsOriginatingAddress: String?,
    val smsRawBody: String,
    // ── نتائج الفحص ─────────────────────────────────────────────────────
    val amountMatch: Boolean,
    val phoneMatch: Boolean,
    val walletMatch: Boolean,
    val senderAddressValid: Boolean,
    val timestampInWindow: Boolean,
    val transactionIdPresent: Boolean,
    // النتيجة النهائية
    val passed: Boolean,
    val failureReason: String?     // null إذا passed=true
)
