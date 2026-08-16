package com.naderai.app.sms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * يُرسل طلبات HTTP إلى Supabase Edge Functions.
 * يُستخدم للـ heartbeat + إرسال نتائج فحص SMS.
 */
object WebhookSender {

    // Supabase Anon Key
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
        ".eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNjaW1sbGdxZHh1dnltZGVpa21uIiwicm9sZSI6ImFub24" +
        "iLCJpYXQiOjE3ODY2ODk3OTQsImV4cCI6MjEwMjI2NTc5NH0" +
        ".intP2QkhXHswRigBpCYb127yNk3VAfj68rpS_Ujvies"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun sendJsonWithBody(
        url: String,
        secret: String,
        body: Map<String, Any>,
        onResult: (success: Boolean, message: String, responseBody: String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject(body).toString()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $ANON_KEY")
                    .addHeader("X-SMS-Webhook-Secret", secret)
                    .addHeader("Content-Type", "application/json")
                    .post(json.toRequestBody(JSON))
                    .build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                val ok = response.isSuccessful && responseBody.contains("\"ok\":true")
                onResult(ok, if (ok) "تم بنجاح" else "خطأ: ${response.code}", responseBody)
            } catch (e: Exception) {
                onResult(false, "فشل الإرسال: ${e.message}", "")
            }
        }
    }

    fun sendJson(
        url: String,
        secret: String,
        body: Map<String, Any>,
        onResult: (Boolean, String) -> Unit
    ) = sendJsonWithBody(url, secret, body) { ok, msg, _ -> onResult(ok, msg) }
}
