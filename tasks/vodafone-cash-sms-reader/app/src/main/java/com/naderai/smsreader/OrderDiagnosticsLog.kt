package com.naderai.smsreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * سجل تشخيصي مركزي — يتتبع كل حدث يمر بالطلب من لحظة وصوله حتى الرد النهائي.
 * thread-safe (CopyOnWriteArrayList)
 * الحد الأقصى 500 حدث في الذاكرة — يُدار تلقائياً.
 */
object OrderDiagnosticsLog {

    // نوع الحدث
    enum class EventType(val label: String, val emoji: String) {
        // دورة حياة الطلب
        ORDER_RECEIVED("وصل للجهاز", "📥"),
        ORDER_SKIPPED("تم تجاهله", "⏭"),
        ORDER_RESET("أُعيد تعيينه", "🔄"),
        SCAN_STARTED("بدأ الفحص", "🔍"),
        SCAN_CACHED("من الكاش", "💾"),
        SCAN_LOCKED("مقفل — فحص آخر جارٍ", "🔒"),
        SMS_SEARCH_STARTED("بدء البحث في SMS", "🔎"),
        SMS_FOUND("وُجدت رسالة مطابقة", "📩"),
        REVIEWING("جاري المراجعة", "🧐"),
        SMS_MATCH_FOUND("تطابق وُجد", "✅"),
        SMS_NOT_FOUND("لم يُوجد تطابق", "❌"),
        SMS_AMOUNT_MISMATCH("مبلغ غير مطابق", "⚠️"),
        SMS_SCAN_FAILED("فشل الفحص", "💥"),
        SMS_PARSE_SUCCESS("تحليل ناجح", "🔤"),
        SMS_PARSE_FAILED("فشل التحليل", "❗"),
        SOURCE_VALIDATION("فحص المصدر", "🛡"),
        AMOUNT_CHECK("فحص المبلغ", "💰"),
        SENDER_CHECK("فحص المُرسِل", "📱"),
        WALLET_CHECK("فحص المحفظة", "👛"),
        TIMESTAMP_CHECK("فحص التوقيت", "🕐"),
        DUPLICATE_CHECK("فحص التكرار", "🔁"),
        VERIFY_SUBMITTED("إرسال التحقق", "📤"),
        VERIFY_RESULT("نتيجة التحقق", "📋"),
        // Fix #5: بيانات ناقصة في payload
        DATA_INCOMPLETE("بيانات ناقصة في الطلب", "⚠"),
        // Fix #5: ACK استلام
        DELIVERY_ACK_SENT("تم إرسال ACK استلام", "📬"),
        DELIVERY_ACK_FAIL("فشل إرسال ACK استلام", "📭"),
        // Fix #5: وضع الاختبار (TEST_ONLY)
        TEST_SCAN_START("اختبار فحص [TEST]", "🧪"),
        TEST_SCAN_RESULT("نتيجة اختبار فحص [TEST]", "🧪"),
        // إرسال للسيرفر
        SERVER_SEND_START("إرسال للسيرفر", "📤"),
        SERVER_RESPONSE_OK("سيرفر: قبول", "🟢"),
        SERVER_RESPONSE_FAIL("سيرفر: رفض", "🔴"),
        SERVER_RESPONSE_ERROR("سيرفر: خطأ", "🚨"),
        // تأكيد يدوي
        MANUAL_CONFIRM_START("تأكيد يدوي", "🖐"),
        MANUAL_CONFIRM_OK("تأكيد يدوي ✓", "🟢"),
        MANUAL_CONFIRM_FAIL("تأكيد يدوي ✗", "🔴"),
        // Fix #6: استرجاع وبدء التطبيق
        APP_START_RECOVERY("استرجاع عند بدء التطبيق", "🚀"),
        LOCAL_ORDERS_LOADED("طلبات محلية محمّلة", "📦"),
        DEVICE_REGISTER_SENT("إرسال تسجيل الجهاز", "📲"),
        DEVICE_REGISTER_OK("تسجيل الجهاز ✓", "✅"),
        DEVICE_REGISTER_FAIL("فشل تسجيل الجهاز", "❌"),
        // حالة النظام
        HEARTBEAT_TASKS("مهام من heartbeat", "💓"),
        SYNC_TASKS("مهام من sync", "🔃"),
        TERMINAL_IGNORED("تجاهل terminal قديم", "🚫"),
        AUTH_ERROR("خطأ مصادقة", "🔑"),
        NETWORK_ERROR("خطأ شبكة", "📡"),
        CONSTRAINT_ERROR("خطأ قاعدة البيانات", "🗃"),
        GENERIC_ERROR("خطأ عام", "❗"),
    }

    data class LogEntry(
        val id: Long,                         // رقم تسلسلي
        val ts: Long,                         // timestamp millis
        val type: EventType,
        val orderNumber: Long?,               // رقم الطلب المرئي
        val requestId: String?,               // UUID الطلب
        val taskId: String?,
        val traceId: String?,                 // Phase-3: trace_id من البداية للنهاية
        val durationMs: Long?,                // Phase-3: مدة الخطوة
        val retryCount: Int,                  // Phase-3: عدد المحاولات
        val details: String?,                 // تفاصيل حرة
        val serverCode: Int?,                 // HTTP code إذا متاح
        val serverResponse: String?,          // أول 300 حرف من رد السيرفر
    ) {
        val tsFormatted: String get() =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ts", ts)
            put("ts_fmt", tsFormatted)
            put("type", type.name)
            put("type_label", type.label)
            put("order_number", orderNumber ?: JSONObject.NULL)
            put("request_id", requestId ?: JSONObject.NULL)
            put("task_id", taskId ?: JSONObject.NULL)
            put("trace_id", traceId ?: JSONObject.NULL)
            put("duration_ms", durationMs ?: JSONObject.NULL)
            put("retry_count", retryCount)
            put("details", details ?: JSONObject.NULL)
            put("server_code", serverCode ?: JSONObject.NULL)
            put("server_response", serverResponse ?: JSONObject.NULL)
        }

        fun toText(): String = buildString {
            append("[${tsFormatted}] ${type.emoji} ${type.label}")
            if (orderNumber != null) append(" | طلب#$orderNumber")
            if (!traceId.isNullOrEmpty()) append(" | trace=${traceId.take(12)}")
            if (!requestId.isNullOrEmpty()) append(" | req=${requestId.take(8)}")
            if (!taskId.isNullOrEmpty()) append(" | task=${taskId.take(8)}")
            if (durationMs != null) append(" | dur=${durationMs}ms")
            if (retryCount > 0) append(" | retry=$retryCount")
            if (!details.isNullOrEmpty()) append(" | $details")
            if (serverCode != null) append(" | HTTP=$serverCode")
            if (!serverResponse.isNullOrEmpty()) append(" | resp=${serverResponse.take(200)}")
        }
    }

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private const val MAX_ENTRIES = 500
    private var idCounter = 0L

    // Fix #5: Deduplication — منع تكرار نفس الحدث خلال نافذة زمنية قصيرة
    // المفتاح: type + requestId + hash أول 40 حرف من details
    private data class DedupKey(val type: EventType, val requestId: String?, val detailsHash: Int)
    private val recentDedupMap = java.util.concurrent.ConcurrentHashMap<DedupKey, Long>()
    private const val DEDUP_WINDOW_MS = 2000L  // 2 ثانية

    // LiveData للمراقبة الفورية من الـ UI
    val liveEntries = androidx.lifecycle.MutableLiveData<List<LogEntry>>(emptyList())

    /** يولّد trace_id موحّد: orderId[:8] + timestamp hex — ثابت طوال عمر الطلب */
    fun buildTraceId(requestId: String?): String {
        val prefix = requestId?.take(8)?.replace("-", "") ?: "00000000"
        val ts = java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(8)
        return "$prefix-$ts"
    }

    @Synchronized
    fun log(
        type: EventType,
        orderNumber: Long? = null,
        requestId: String? = null,
        taskId: String? = null,
        traceId: String? = null,
        durationMs: Long? = null,
        retryCount: Int = 0,
        details: String? = null,
        serverCode: Int? = null,
        serverResponse: String? = null,
    ) {
        // Fix #5: Deduplication — نتحقق أولاً هل سُجّل نفس الحدث خلال 2 ثانية
        // نستثني أحداث الأخطاء (AUTH_ERROR, SERVER_RESPONSE_ERROR) من dedup حتى لا نخفيها
        val deduplicatable = type !in setOf(
            EventType.AUTH_ERROR, EventType.SERVER_RESPONSE_ERROR,
            EventType.GENERIC_ERROR, EventType.NETWORK_ERROR
        )
        if (deduplicatable) {
            val key = DedupKey(type, requestId, details?.take(40)?.hashCode() ?: 0)
            val lastTs = recentDedupMap[key]
            val nowMs = System.currentTimeMillis()
            if (lastTs != null && nowMs - lastTs < DEDUP_WINDOW_MS) {
                // نفس الحدث خلال نافذة dedup — نتجاهله ونُسجّل في Logcat فقط
                android.util.Log.v("DiagLog", "DEDUP_SKIP | type=${type.name} req=${requestId?.take(8)} — same event within ${DEDUP_WINDOW_MS}ms")
                return
            }
            recentDedupMap[key] = nowMs
            // تنظيف القديم كل 100 إدخال تقريباً
            if (recentDedupMap.size > 200) {
                val cutoff = nowMs - DEDUP_WINDOW_MS * 10
                recentDedupMap.entries.removeAll { it.value < cutoff }
            }
        }

        val entry = LogEntry(
            id = ++idCounter,
            ts = System.currentTimeMillis(),
            type = type,
            orderNumber = orderNumber,
            requestId = requestId,
            taskId = taskId,
            traceId = traceId,
            durationMs = durationMs,
            retryCount = retryCount,
            details = details,
            serverCode = serverCode,
            serverResponse = serverResponse?.take(300),
        )
        entries.add(0, entry) // أحدث أولاً
        if (entries.size > MAX_ENTRIES) {
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        }
        android.util.Log.d("DiagLog", entry.toText())
        liveEntries.postValue(entries.toList())
    }

    fun getAll(): List<LogEntry> = entries.toList()

    fun getForOrder(requestId: String): List<LogEntry> =
        entries.filter { it.requestId == requestId }

    /** أحدث traceId لطلب معيّن */
    fun getTraceId(requestId: String): String? =
        entries.firstOrNull { it.requestId == requestId && !it.traceId.isNullOrEmpty() }?.traceId

    /** آخر خطأ من أي طلب */
    fun getLastError(): LogEntry? =
        entries.firstOrNull { it.type in setOf(EventType.GENERIC_ERROR, EventType.NETWORK_ERROR, EventType.AUTH_ERROR, EventType.SERVER_RESPONSE_ERROR, EventType.SMS_SCAN_FAILED, EventType.SMS_PARSE_FAILED) }

    /** آخر event من نوع معيّن */
    fun getLastOfType(type: EventType): LogEntry? = entries.firstOrNull { it.type == type }

    fun getRecent(n: Int = 100): List<LogEntry> = entries.take(n)

    fun clear() {
        entries.clear()
        liveEntries.postValue(emptyList())
    }

    /** تصدير كل السجل كـ JSON */
    fun exportJson(): String {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            put("entry_count", entries.size)
            put("entries", arr)
        }.toString(2)
    }

    /** تصدير كـ نص قابل للقراءة */
    fun exportText(): String = buildString {
        appendLine("=== سجل التشخيصات — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ===")
        appendLine("إجمالي الأحداث: ${entries.size}")
        appendLine("=".repeat(70))
        entries.forEach { appendLine(it.toText()) }
    }

    /** حفظ إلى ملف خارجي ويُرجع المسار */
    fun saveToFile(context: Context, format: String = "json"): File? {
        return try {
            val dir = context.getExternalFilesDir("diagnostics") ?: context.filesDir
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "diag_$ts.$format")
            val content = if (format == "json") exportJson() else exportText()
            file.writeText(content, Charsets.UTF_8)
            android.util.Log.i("DiagLog", "Saved diagnostics to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            android.util.Log.e("DiagLog", "Failed to save diagnostics: ${e.message}")
            null
        }
    }
}
