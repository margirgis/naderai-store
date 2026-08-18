package com.naderai.smsreader

/**
 * بيانات رسالة Vodafone Cash المُستخرَجة من SMS.
 * مستخدمة من SmsParser و TaskScanner.
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
