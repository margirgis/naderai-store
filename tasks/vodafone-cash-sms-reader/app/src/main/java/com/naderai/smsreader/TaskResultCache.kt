package com.naderai.smsreader

import android.content.Context

/**
 * ذاكرة محلية لنتائج فحص المهام، لمنع إعادة الفحص المستمر لنفس الطلب.
 * يحتفظ بالنتيجة ويحاول إرسالها للسيرفر بدلاً من إعادة فحص الرسائل.
 */
object TaskResultCache {

    private const val PREFS_NAME = "naderai_task_results"
    private const val KEY_PREFIX = "task_result_"
    private const val MAX_SEND_RETRIES = 3

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, taskId: String): CachedResult? {
        val json = prefs(context).getString("$KEY_PREFIX$taskId", null) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            CachedResult(
                taskId = taskId,
                status = obj.getString("status"),
                resultData = obj.optString("result_data").takeIf { it.isNotEmpty() },
                failureReason = obj.optString("failure_reason").takeIf { it.isNotEmpty() },
                retryCount = obj.optInt("retry_count", 0),
                scannedAt = obj.getLong("scanned_at")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun put(context: Context, taskId: String, result: TaskScanner.ScanResult) {
        val status = when (result) {
            is TaskScanner.ScanResult.Success -> "success"
            is TaskScanner.ScanResult.AmountMismatch -> "amount_mismatch"
            is TaskScanner.ScanResult.NotFound -> "not_found"
            is TaskScanner.ScanResult.Failure -> "failure"
        }
        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        // حفظ result_data بالكامل لجميع الحالات، ليس فقط success
        val resultData = when (result) {
            is TaskScanner.ScanResult.Success -> {
                val m = result.message
                org.json.JSONObject().apply {
                    put("sender_phone", m.senderPhone ?: "")
                    put("sender_name", m.senderName ?: "")
                    put("amount", m.amount ?: 0.0)
                    put("transaction_id", m.transactionId ?: "")
                    put("receiver_wallet", m.receiverWallet ?: "")
                    put("sms_body", m.body)
                    put("scanned_at", isoFmt.format(java.util.Date(m.date)))
                }.toString()
            }
            is TaskScanner.ScanResult.AmountMismatch -> {
                // حفظ المبالغ لمساعدة المسؤول على المراجعة
                org.json.JSONObject().apply {
                    put("found_amount", result.foundAmount)
                    put("expected_amount", result.expectedAmount)
                    put("sender_phone", result.message.senderPhone ?: "")
                    put("scanned_at", isoFmt.format(java.util.Date()))
                }.toString()
            }
            is TaskScanner.ScanResult.NotFound -> {
                org.json.JSONObject().apply {
                    put("reason", result.reason ?: "not_found")
                    put("scanned_at", isoFmt.format(java.util.Date()))
                }.toString()
            }
            is TaskScanner.ScanResult.Failure -> {
                org.json.JSONObject().apply {
                    put("reason", result.reason ?: "failure")
                    put("scanned_at", isoFmt.format(java.util.Date()))
                }.toString()
            }
        }
        val failureReason = when (result) {
            is TaskScanner.ScanResult.Failure -> result.reason
            is TaskScanner.ScanResult.NotFound -> result.reason
            is TaskScanner.ScanResult.AmountMismatch ->
                "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"
            else -> null
        }
        val existing = get(context, taskId)
        val json = org.json.JSONObject().apply {
            put("status", status)
            put("result_data", resultData ?: "")
            put("failure_reason", failureReason ?: "")
            put("retry_count", existing?.retryCount ?: 0)
            put("scanned_at", System.currentTimeMillis())
        }.toString()
        prefs(context).edit().putString("$KEY_PREFIX$taskId", json).apply()
        android.util.Log.d("TaskResultCache", "Cached $taskId → status=$status")
    }

    fun incrementRetry(context: Context, taskId: String) {
        val cached = get(context, taskId) ?: return
        if (cached.retryCount >= MAX_SEND_RETRIES) return
        val json = org.json.JSONObject().apply {
            put("status", cached.status)
            put("result_data", cached.resultData ?: "")
            put("failure_reason", cached.failureReason ?: "")
            put("retry_count", cached.retryCount + 1)
            put("scanned_at", cached.scannedAt)
        }.toString()
        prefs(context).edit().putString("$KEY_PREFIX$taskId", json).apply()
    }

    fun shouldRetry(context: Context, taskId: String): Boolean {
        val cached = get(context, taskId) ?: return true
        return cached.retryCount < MAX_SEND_RETRIES
    }

    fun remove(context: Context, taskId: String) {
        prefs(context).edit().remove("$KEY_PREFIX$taskId").apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    data class CachedResult(
        val taskId: String,
        val status: String,
        val resultData: String?,
        val failureReason: String?,
        val retryCount: Int,
        val scannedAt: Long
    )
}
