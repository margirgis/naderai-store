package com.naderai.smsreader

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.naderai.smsreader.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * شاشة تسجيل دخول الأدمن.
 * بعد نجاح تسجيل الدخول يتم حفظ التوكنات المشفّرة والانتقال للتطبيق الرئيسي.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // إذا كان الأدمن مسجّلاً ومازال التوكن صالحاً، نتجاوز شاشة الدخول
        if (AdminSession.isLoggedIn(this)) {
            goToMain()
            return
        }

        binding.loginButton.setOnClickListener { attemptLogin() }
        binding.skipLoginButton.setOnClickListener { goToMain() }
    }

    private fun attemptLogin() {
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val rawUrl = binding.supabaseUrlInput.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "أدخل البريد وكلمة المرور", Toast.LENGTH_SHORT).show()
            return
        }

        val loginUrl = SupabaseConfig.getAdminLoginUrl(rawUrl)
        if (loginUrl.isNullOrEmpty()) {
            Toast.makeText(this, "رابط Supabase غير صالح", Toast.LENGTH_SHORT).show()
            return
        }

        // احفظ رابط Supabase لاستخدامه لاحقاً
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(MainActivity.KEY_WEBHOOK_URL, rawUrl)
            .apply()

        binding.loginProgress.visibility = View.VISIBLE
        binding.loginButton.isEnabled = false

        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }.toString()

        val request = Request.Builder()
            .url(loginUrl)
            .addHeader("Authorization", "Bearer ${WebhookSender.ANON_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.loginProgress.visibility = View.GONE
                    binding.loginButton.isEnabled = true
                    Toast.makeText(this@LoginActivity, "فشل الاتصال: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                runOnUiThread {
                    binding.loginProgress.visibility = View.GONE
                    binding.loginButton.isEnabled = true
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(responseBody)
                            val accessToken = json.getString("access_token")
                            val refreshToken = json.getString("refresh_token")
                            val expiresAt = json.getLong("expires_at")
                            AdminSession.save(this@LoginActivity, accessToken, refreshToken, expiresAt, email)
                            SyncTriggers.onLogin(this@LoginActivity)
                            Toast.makeText(this@LoginActivity, "✓ تم تسجيل الدخول", Toast.LENGTH_SHORT).show()
                            goToMain()
                        } catch (e: Exception) {
                            Toast.makeText(this@LoginActivity, "خطأ في تحليل الاستجابة: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val reason = try {
                            JSONObject(responseBody).getString("error")
                        } catch (_: Exception) {
                            "خطأ ${response.code}"
                        }
                        Toast.makeText(this@LoginActivity, "فشل الدخول: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
