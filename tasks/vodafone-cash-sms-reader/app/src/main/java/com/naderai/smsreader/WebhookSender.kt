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

    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNjaW1sbGdxZHh1dnltZGVpa21uIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY2ODk3OTQsImV4cCI6MjEwMjI2NTc5NH0.intP2QkhXHswRigBpCYb127yNk3VAfj68rpS_Ujvies"

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
                    onResult(false, "فشل الإرسال: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string() ?: ""
                    val success = response.isSuccessful && responseBody.contains("\"ok\":true")
                    onResult(success, if (success) "تم إرسال التأكيد بنجاح" else "استجابة الخادم: ${response.code}")
                }
            })
        }
    }
}
