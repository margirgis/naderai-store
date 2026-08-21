package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase-3 Offline Retry Queue — Exponential Backoff + Idempotency + Max Retries
 *
 * Backoff schedule (ms): 2000, 4000, 8000, 16000, 30000
 * MAX_RETRIES = 5 — بعدها يُهمَل الإدخال ويُسجَّل.
 * منع الـ infinite loop: كل محاولة تنتظر delay مناسب.
 * الـ idempotency_key يمنع التكرار.
 */
object RetryQueue {
    private const val TAG = "RetryQueue"
    private const val PREFS = "retry_queue"
    private const val KEY_QUEUE = "pending_results"
    private const val MAX_RETRIES = 5

    // Exponential backoff: 2^n * 1000ms ، حد أقصى 30 ثانية
    private val BACKOFF_STEPS_MS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)

    @Volatile private var isDraining = false

    data class QueuedResult(
        val taskId: String,
        val idempotencyKey: String,
        val body: Map<String, Any>,
        val retryCount: Int = 0,
        val queuedAt: Long = System.currentTimeMillis()
    )

    fun enqueue(context: Context, taskId: String, idempotencyKey: String, body: Map<String, Any>) {
        val prefs = prefs(context)
        val arr = loadArray(prefs)
        // منع تكرار نفس idempotency_key
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("idempotency_key") == idempotencyKey) {
                Log.d(TAG, "Already queued — skipping duplicate: $idempotencyKey")
                return
            }
        }
        val entry = JSONObject().apply {
            put("task_id", taskId)
            put("idempotency_key", idempotencyKey)
            put("body", JSONObject(body))
            put("retry_count", 0)
            put("queued_at", System.currentTimeMillis())
        }
        arr.put(entry)
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
        Log.d(TAG, "Enqueued task=$taskId (queue size: ${arr.length()})")
    }

    /**
     * يُستدعى عند استعادة الشبكة. يستنزف الطابور مع exponential backoff.
     * لا يُشغَّل أكثر من مرة في نفس الوقت.
     */
    fun drainOnReconnect(context: Context, webhookUrl: String, secret: String) {
        if (isDraining) {
            Log.d(TAG, "Already draining — skip duplicate call")
            return
        }
        val prefs = prefs(context)
        val arr = loadArray(prefs)
        if (arr.length() == 0) return

        Log.d(TAG, "Draining ${arr.length()} queued results (backoff mode)")
        isDraining = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val remaining = JSONArray()

                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val retryCount = entry.optInt("retry_count", 0)

                    if (retryCount >= MAX_RETRIES) {
                        Log.w(TAG, "MAX_RETRIES reached — dropping task=${entry.optString("task_id")}")
                        OrderDiagnosticsLog.log(
                            OrderDiagnosticsLog.EventType.GENERIC_ERROR,
                            details = "DROPPED after $MAX_RETRIES retries: ${entry.optString("task_id")}"
                        )
                        continue // يُهمَل بلا credit
                    }

                    // Backoff delay قبل كل محاولة إعادة
                    val backoffMs = BACKOFF_STEPS_MS.getOrElse(retryCount) { BACKOFF_STEPS_MS.last() }
                    if (retryCount > 0) {
                        Log.d(TAG, "Backoff ${backoffMs}ms before retry#$retryCount for ${entry.optString("task_id")}")
                        delay(backoffMs)
                    }

                    val bodyJson = entry.getJSONObject("body")
                    val bodyMap = mutableMapOf<String, Any>()
                    bodyJson.keys().forEach { k -> bodyMap[k] = bodyJson.get(k) }

                    var sent = false
                    WebhookSender.sendJsonWithBody(webhookUrl, secret, bodyMap) { success, _, _ ->
                        sent = success
                    }
                    // انتظر اكتمال الإرسال الـ async
                    delay(2_000)

                    if (!sent) {
                        entry.put("retry_count", retryCount + 1)
                        remaining.put(entry)
                        Log.w(TAG, "Failed send — queued for retry#${retryCount + 1}")
                    } else {
                        Log.d(TAG, "Sent successfully: ${entry.optString("task_id")}")
                    }
                }
                prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
                Log.d(TAG, "Drain complete. Remaining: ${remaining.length()}")
            } finally {
                isDraining = false
            }
        }
    }

    fun size(context: Context): Int = loadArray(prefs(context)).length()

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_QUEUE).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadArray(prefs: SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
}
