package com.naderai.smsreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebhookSender {

    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNjaW1sbGdxZHh1dnltZGVpa21uIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY2ODk3OTQsImV4cCI6MjEwMjI2NTc5NH0.intP2QkhXHswRigBpCYb127yNk3VAfj68rpS_Ujvies"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun send(
        url: String,
        secret: String,
        body: Map<String, String>,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        sendJson(url, secret, body, onResult)
    }

    fun sendJson(
        url: String,
        secret: String,
        body: Map<String, Any>,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        sendJsonWithBody(url, secret, body) { success, message, _ ->
            onResult(success, message)
        }
    }

    fun sendJsonWithBody(
        url: String,
        secret: String,
        body: Map<String, Any>,
        onResult: (success: Boolean, message: String, responseBody: String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = JSONObject(body).toString()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $ANON_KEY")
                .addHeader("X-SMS-Webhook-Secret", secret)
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    android.util.Log.e("WebhookSender", "POST $url FAILED: ${e.message}")
                    onResult(false, "فشل الإرسال: ${e.message}", "")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    val success = response.isSuccessful && responseBody.contains("\"ok\":true")
                    android.util.Log.d("WebhookSender", "POST $url HTTP ${response.code} ok=$success length=${responseBody.length}")
                    onResult(success, if (success) "تم إرسال التأكيد بنجاح" else "استجابة الخادم: ${response.code}", responseBody)
                }
            })
        }
    }

    /**
     * إرسال طلب للـ admin endpoints.
     * إذا تم تمرير adminToken يُستخدم كـ Bearer token بدلاً من ANON_KEY
     * (مطلوب لـ admin-manual-confirm الذي يستخدم requireAdmin).
     */
    fun sendAdminJson(
        url: String,
        body: Map<String, Any>,
        adminToken: String? = null,
        onResult: (success: Boolean, message: String, responseBody: String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = JSONObject(body).toString()
            val authToken = if (!adminToken.isNullOrEmpty()) adminToken else ANON_KEY
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $authToken")
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    android.util.Log.e("WebhookSender", "sendAdminJson FAILED url=$url: ${e.message}")
                    onResult(false, "فشل الإرسال: ${e.message}", "")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    val success = response.isSuccessful && responseBody.contains("\"ok\":true")
                    android.util.Log.d("WebhookSender", "sendAdminJson $url HTTP ${response.code} ok=$success")
                    onResult(success, if (success) "تم" else "استجابة الخادم: ${response.code}", responseBody)
                }
            })
        }
    }

    /**
     * إرسال نتيجة فحص SMS عبر endpoint الأدمن.
     * يجب أن يحتوي body على access_token و refresh_token من AdminSession.
     * نُمرر access_token كـ Bearer header أيضاً لأن admin-task-result يتحقق منه في الـ header.
     */
    fun sendAdminTaskResult(
        url: String,
        body: Map<String, Any>,
        onResult: (success: Boolean, message: String, responseBody: String) -> Unit
    ) {
        // استخرج access_token من الـ body ومرّره كـ Bearer header
        val accessToken = body["access_token"] as? String
        sendAdminJson(url, body, adminToken = accessToken, onResult = onResult)
    }
}
