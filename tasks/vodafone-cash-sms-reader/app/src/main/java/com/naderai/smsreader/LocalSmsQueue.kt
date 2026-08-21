package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * طابور محلي للرسائل المستلمة من فودافون كاش.
 *
 * السيناريو المحمي:
 *   1. SMS تصل → تُحفظ هنا فوراً (مع transaction_id, amount, sender_phone).
 *   2. عند وصول task من السيرفر → يفحص الطابور أولاً قبل قراءة صندوق الرسائل.
 *   3. لو وجد تطابق → يرسل النتيجة فوراً بدون انتظار 20 ثانية.
 *   4. الرسائل تُحذف بعد 60 دقيقة أو بعد مطابقتها.
 *
 * الضمانات:
 *   - amount match: EXACT (نفس المنطق في TaskScanner)
 *   - sender_phone match: مطلوب دائماً
 *   - transaction_id: يُسجَّل لمنع التكرار
 */
object LocalSmsQueue {

    private const val TAG = "LocalSmsQueue"
    private const val PREFS_FILE = "local_sms_queue"
    private const val KEY_QUEUE = "sms_queue"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24 ساعة — أي SMS عمره أقل مقبول
    private const val MAX_SIZE = 50                   // حد أقصى للذاكرة

    data class QueuedSms(
        val transactionId: String?,
        val senderPhone: String?,       // مُعيَّر بدون 0 في البداية (9 أرقام)
        val senderName: String?,
        val amount: Double?,
        val receiverWallet: String?,
        val smsBody: String,
        val receivedAt: Long            // System.currentTimeMillis()
    )

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ── إضافة رسالة للطابور ───────────────────────────────────────────────
    fun push(context: Context, sms: QueuedSms) {
        val prefs = getPrefs(context)
        val list = loadRaw(prefs).toMutableList()

        // تجنّب التكرار: لو transaction_id موجود بالفعل، لا نضيف مرة ثانية
        if (sms.transactionId != null && list.any { it.transactionId == sms.transactionId }) {
            Log.d(TAG, "push: duplicate transactionId=${sms.transactionId}, skipped")
            return
        }

        // حذف الرسائل القديمة قبل الإضافة
        val now = System.currentTimeMillis()
        val fresh = list.filter { now - it.receivedAt < MAX_AGE_MS }
        val updated = (fresh + sms).takeLast(MAX_SIZE)

        saveRaw(prefs, updated)
        Log.d(TAG, "push: queued SMS txId=${sms.transactionId} amount=${sms.amount} phone=${sms.senderPhone} queueSize=${updated.size}")
    }

    // ── البحث عن تطابق لطلب معين ─────────────────────────────────────────
    fun findMatch(context: Context, task: TaskScanner.Task): QueuedSms? {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val list = loadRaw(prefs).filter { now - it.receivedAt < MAX_AGE_MS }

        // phone مطلوب دائماً في الطلب
        val requestedRaw = task.senderPhoneRequested?.trim().orEmpty()
        if (requestedRaw.isEmpty()) {
            Log.w(TAG, "findMatch: task ${task.taskId} has no senderPhoneRequested — cannot match from queue")
            return null
        }
        val normalizedRequested = normalizeEgyptianPhone(requestedRaw)
        if (normalizedRequested.isEmpty()) return null

        val targetAmount = task.fingerprintAmount ?: task.amountRequested

        return list.firstOrNull { sms ->
            val phoneMatch = sms.senderPhone != null && sms.senderPhone == normalizedRequested
            val amountMatch = sms.amount != null && targetAmount > 0 &&
                    Math.round(sms.amount * 100) == Math.round(targetAmount * 100)
            phoneMatch && amountMatch
        }
    }

    // ── حذف رسالة بعد مطابقتها ───────────────────────────────────────────
    fun remove(context: Context, transactionId: String?) {
        if (transactionId == null) return
        val prefs = getPrefs(context)
        val list = loadRaw(prefs).filterNot { it.transactionId == transactionId }
        saveRaw(prefs, list)
        Log.d(TAG, "remove: txId=$transactionId removed from queue")
    }

    // ── تنظيف الرسائل القديمة ─────────────────────────────────────────────
    fun pruneExpired(context: Context) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val fresh = loadRaw(prefs).filter { now - it.receivedAt < MAX_AGE_MS }
        saveRaw(prefs, fresh)
    }

    // ── إحصاء للـ diagnostics ─────────────────────────────────────────────
    fun size(context: Context): Int = loadRaw(getPrefs(context)).size

    // ── Serialization ─────────────────────────────────────────────────────
    private fun loadRaw(prefs: SharedPreferences): List<QueuedSms> {
        return try {
            val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                QueuedSms(
                    transactionId = obj.takeIfString("txId"),
                    senderPhone   = obj.takeIfString("phone"),
                    senderName    = obj.takeIfString("name"),
                    amount        = if (obj.has("amount") && !obj.isNull("amount")) obj.getDouble("amount") else null,
                    receiverWallet= obj.takeIfString("wallet"),
                    smsBody       = obj.optString("body", ""),
                    receivedAt    = obj.optLong("at", 0L)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadRaw failed: ${e.message}")
            emptyList()
        }
    }

    private fun saveRaw(prefs: SharedPreferences, list: List<QueuedSms>) {
        try {
            val arr = JSONArray()
            list.forEach { sms ->
                arr.put(JSONObject().apply {
                    putOpt("txId",   sms.transactionId)
                    putOpt("phone",  sms.senderPhone)
                    putOpt("name",   sms.senderName)
                    if (sms.amount != null) put("amount", sms.amount)
                    putOpt("wallet", sms.receiverWallet)
                    put("body",      sms.smsBody)
                    put("at",        sms.receivedAt)
                })
            }
            prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveRaw failed: ${e.message}")
        }
    }

    private fun JSONObject.takeIfString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotEmpty() } else null

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
}
