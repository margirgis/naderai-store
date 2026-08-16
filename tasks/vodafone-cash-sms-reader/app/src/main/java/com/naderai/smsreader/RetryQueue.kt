package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline retry queue. Persists unsent task results to SharedPreferences.
 * On reconnect, drains the queue with idempotency protection.
 */
object RetryQueue {
    private const val TAG = "RetryQueue"
    private const val PREFS = "retry_queue"
    private const val KEY_QUEUE = "pending_results"
    private const val MAX_RETRIES = 5

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
        // Avoid duplicate entries for same idempotency key
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("idempotency_key") == idempotencyKey) {
                Log.d(TAG, "Already queued: $idempotencyKey")
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
        Log.d(TAG, "Enqueued task $taskId (queue size: ${arr.length()})")
    }

    fun drainOnReconnect(context: Context, webhookUrl: String, secret: String) {
        val prefs = prefs(context)
        val arr = loadArray(prefs)
        if (arr.length() == 0) return

        Log.d(TAG, "Draining ${arr.length()} queued results")
        val remaining = JSONArray()

        CoroutineScope(Dispatchers.IO).launch {
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val retryCount = entry.optInt("retry_count", 0)
                if (retryCount >= MAX_RETRIES) {
                    Log.w(TAG, "Dropping after $MAX_RETRIES retries: ${entry.optString("task_id")}")
                    continue
                }
                val bodyJson = entry.getJSONObject("body")
                val bodyMap = mutableMapOf<String, Any>()
                bodyJson.keys().forEach { k -> bodyMap[k] = bodyJson.get(k) }

                var sent = false
                WebhookSender.sendJsonWithBody(webhookUrl, secret, bodyMap) { success, _, _ ->
                    sent = success
                }
                // Give sendJsonWithBody time to complete (it's async internally)
                Thread.sleep(2000)
                if (!sent) {
                    entry.put("retry_count", retryCount + 1)
                    remaining.put(entry)
                }
            }
            prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
            Log.d(TAG, "Queue drained. Remaining: ${remaining.length()}")
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
