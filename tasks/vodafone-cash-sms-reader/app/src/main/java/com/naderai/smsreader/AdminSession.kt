package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * يدير جلسة تسجيل دخول الأدمن مع دورة حياة كاملة للتوكن:
 * - تخزين مشفّر (EncryptedSharedPreferences) مع fallback عادي
 * - كشف انتهاء الصلاحية قبل 5 دقائق (EXPIRY_BUFFER_MS)
 * - تجديد تلقائي عبر refreshSession قبل أي API call
 * - حالة SESSION_EXPIRED مصنّفة عند فشل التجديد
 */
object AdminSession {

    private const val TAG = "AdminSession"
    private const val PREFS_FILE = "admin_session_encrypted"
    private const val FALLBACK_PREFS_FILE = "admin_session_fallback"
    private const val KEY_ACCESS_TOKEN  = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT    = "expires_at"
    private const val KEY_ADMIN_EMAIL   = "admin_email"
    private const val KEY_LOGGED_IN     = "logged_in"

    // نبدأ التجديد قبل انتهاء الصلاحية بـ 5 دقائق
    private val EXPIRY_BUFFER_MS = TimeUnit.MINUTES.toMillis(5)

    // ── Prefs helpers ─────────────────────────────────────────────────────────

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE, masterKeyAlias, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getFallbackPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)

    private fun getPrefs(context: Context): SharedPreferences = try {
        getEncryptedPrefs(context)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Encrypted prefs failed, using fallback: ${e.message}")
        getFallbackPrefs(context)
    }

    // ── كتابة / قراءة ─────────────────────────────────────────────────────────

    fun save(context: Context, accessToken: String, refreshToken: String, expiresAt: Long, email: String) {
        getPrefs(context).edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            putString(KEY_ADMIN_EMAIL, email)
            putBoolean(KEY_LOGGED_IN, true)
            apply()
        }
        android.util.Log.i(TAG, "Session saved — expires=${java.time.Instant.ofEpochMilli(expiresAt)}")
    }

    fun clear(context: Context) {
        try { getEncryptedPrefs(context).edit().clear().apply() } catch (_: Exception) {}
        getFallbackPrefs(context).edit().clear().apply()
        android.util.Log.i(TAG, "Session cleared")
    }

    fun isLoggedIn(context: Context): Boolean = try {
        val prefs = getPrefs(context)
        prefs.getBoolean(KEY_LOGGED_IN, false) &&
            (!prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty() ||
             !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty())
    } catch (e: Exception) {
        android.util.Log.e(TAG, "isLoggedIn: ${e.message}")
        false
    }

    fun accessToken(context: Context): String? = try {
        getPrefs(context).getString(KEY_ACCESS_TOKEN, null)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "accessToken: ${e.message}")
        null
    }

    fun refreshToken(context: Context): String? = try {
        getPrefs(context).getString(KEY_REFRESH_TOKEN, null)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "refreshToken: ${e.message}")
        null
    }

    fun email(context: Context): String? = try {
        getPrefs(context).getString(KEY_ADMIN_EMAIL, null)
    } catch (e: Exception) { null }

    fun updateTokens(context: Context, accessToken: String?, refreshToken: String?, expiresAt: Long?) {
        try {
            val edit = getPrefs(context).edit()
            if (!accessToken.isNullOrEmpty())  edit.putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrEmpty()) edit.putString(KEY_REFRESH_TOKEN, refreshToken)
            if (expiresAt != null)             edit.putLong(KEY_EXPIRES_AT, expiresAt)
            edit.apply()
            android.util.Log.d(TAG, "Tokens updated — expiresAt=${expiresAt?.let { java.time.Instant.ofEpochMilli(it) }}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "updateTokens: ${e.message}")
        }
    }

    // ── Session health ─────────────────────────────────────────────────────────

    /**
     * هل انتهت صلاحية الـ access token (أو اقتربت من الانتهاء)؟
     * نعتبر الصلاحية "منتهية" قبل 5 دقائق من وقت expires_at.
     */
    fun isTokenExpired(context: Context): Boolean {
        val expiresAt = try {
            getPrefs(context).getLong(KEY_EXPIRES_AT, 0L)
        } catch (_: Exception) { 0L }
        if (expiresAt <= 0L) return false  // لا يوجد وقت انتهاء → اعتبره صالحاً
        return System.currentTimeMillis() >= (expiresAt - EXPIRY_BUFFER_MS)
    }

    /**
     * يُرجع access token صالحاً:
     * - إذا كان التوكن منتهياً يحاول التجديد عبر Supabase Auth REST API.
     * - عند نجاح التجديد يحفظ التوكنات الجديدة ويُرجع access_token الجديد.
     * - عند فشل التجديد (401 أو شبكة) يُرجع null مع تسجيل SESSION_EXPIRED.
     * - NEVER يعطي access token منتهي الصلاحية للـ API calls.
     */
    fun getValidAccessToken(context: Context, onResult: (token: String?, expired: Boolean) -> Unit) {
        val currentToken = accessToken(context)
        if (currentToken.isNullOrEmpty()) {
            android.util.Log.w(TAG, "SESSION_EXPIRED | no access_token stored")
            OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.AUTH_ERROR, details = "SESSION_EXPIRED: no access_token stored")
            onResult(null, true)
            return
        }
        if (!isTokenExpired(context)) {
            // التوكن ما زال صالحاً
            onResult(currentToken, false)
            return
        }
        // التوكن منتهٍ أو على وشك الانتهاء → نجدد
        val refresh = refreshToken(context)
        if (refresh.isNullOrEmpty()) {
            android.util.Log.w(TAG, "SESSION_EXPIRED | token expired + no refresh_token")
            OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.AUTH_ERROR, details = "SESSION_EXPIRED: expired, no refresh_token")
            onResult(null, true)
            return
        }
        android.util.Log.d(TAG, "Token expired — attempting refresh via Supabase Auth")
        refreshSession(context, refresh, onResult)
    }

    /**
     * يُجدّد الجلسة عبر POST /auth/v1/token?grant_type=refresh_token
     * مُستقل عن WebhookSender لمنع circular dependency.
     */
    private fun refreshSession(context: Context, refreshToken: String, onResult: (token: String?, expired: Boolean) -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val supabaseUrl = SupabaseConfig.supabaseUrl.trimEnd('/')
                val anonKey = WebhookSender.ANON_KEY
                val url = "$supabaseUrl/auth/v1/token?grant_type=refresh_token"
                val jsonBody = org.json.JSONObject().apply {
                    put("refresh_token", refreshToken)
                }.toString()

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        android.util.Log.w(TAG, "SESSION_EXPIRED | refresh failed HTTP ${response.code}: $body")
                        OrderDiagnosticsLog.log(
                            OrderDiagnosticsLog.EventType.AUTH_ERROR,
                            details = "SESSION_EXPIRED: refresh HTTP ${response.code}",
                            serverCode = response.code
                        )
                        onResult(null, true)
                        return@launch
                    }
                    val json = org.json.JSONObject(body)
                    val newAccess  = json.optString("access_token")
                    val newRefresh = json.optString("refresh_token")
                    val expiresIn  = json.optLong("expires_in", 3600L)
                    val expiresAt  = System.currentTimeMillis() + expiresIn * 1000L
                    if (newAccess.isNotEmpty()) {
                        updateTokens(context, newAccess, newRefresh.takeIf { it.isNotEmpty() }, expiresAt)
                        android.util.Log.i(TAG, "Session refreshed OK — new expiry=${java.time.Instant.ofEpochMilli(expiresAt)}")
                        onResult(newAccess, false)
                    } else {
                        android.util.Log.w(TAG, "SESSION_EXPIRED | refresh OK but no access_token in response")
                        OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.AUTH_ERROR, details = "SESSION_EXPIRED: empty access_token in refresh response")
                        onResult(null, true)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "SESSION_EXPIRED | refresh exception: ${e.message}", e)
                OrderDiagnosticsLog.log(OrderDiagnosticsLog.EventType.AUTH_ERROR, details = "SESSION_EXPIRED: refresh exception: ${e.message}")
                onResult(null, true)
            }
        }
    }

    // ── Coroutine suspend version (للاستخدام من coroutine context) ────────────

    suspend fun getValidAccessTokenSuspend(context: Context): Pair<String?, Boolean> {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            getValidAccessToken(context) { token, expired ->
                if (cont.isActive) cont.resume(Pair(token, expired)) {}
            }
        }
    }
}

// ── Extension لاستخدام okhttp3 MediaType داخل AdminSession ─────────────────
private fun String.toMediaType(): okhttp3.MediaType = okhttp3.MediaType.Companion.parse(this)!!
private fun String.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody =
    okhttp3.RequestBody.create(mediaType, this.toByteArray(Charsets.UTF_8))
