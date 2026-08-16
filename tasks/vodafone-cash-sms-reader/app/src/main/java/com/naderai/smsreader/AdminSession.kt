package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * يدير جلسة تسجيل دخول الأدمن في التطبيق.
 * يحاول تخزين التوكنات بشكل مشفّر باستخدام AndroidX Security،
 * ولو فشل التشفير (مشاكل Keystore على بعض الأجهزة) بيرجع لـ SharedPreferences عادي.
 */
object AdminSession {

    private const val PREFS_FILE = "admin_session_encrypted"
    private const val FALLBACK_PREFS_FILE = "admin_session_fallback"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_ADMIN_EMAIL = "admin_email"
    private const val KEY_LOGGED_IN = "logged_in"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getFallbackPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            getEncryptedPrefs(context)
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "Encrypted prefs failed, fallback: ${e.message}", e)
            getFallbackPrefs(context)
        }
    }

    fun save(context: Context, accessToken: String, refreshToken: String, expiresAt: Long, email: String) {
        getPrefs(context).edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            putString(KEY_ADMIN_EMAIL, email)
            putBoolean(KEY_LOGGED_IN, true)
            apply()
        }
    }

    fun clear(context: Context) {
        try {
            getEncryptedPrefs(context).edit().clear().apply()
        } catch (_: Exception) {}
        getFallbackPrefs(context).edit().clear().apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return try {
            val prefs = getPrefs(context)
            if (!prefs.getBoolean(KEY_LOGGED_IN, false)) return false
            // نعتبر الجلسة موجودة إذا كان لدينا access token أو refresh token،
            // لأن Edge Function يمكنه تجديد التوكن إذا انتهت صلاحيته.
            !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty() ||
                    !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty()
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "isLoggedIn failed: ${e.message}", e)
            false
        }
    }

    fun accessToken(context: Context): String? {
        return try {
            getPrefs(context).getString(KEY_ACCESS_TOKEN, null)
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "accessToken failed: ${e.message}", e)
            null
        }
    }

    fun refreshToken(context: Context): String? {
        return try {
            getPrefs(context).getString(KEY_REFRESH_TOKEN, null)
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "refreshToken failed: ${e.message}", e)
            null
        }
    }

    fun email(context: Context): String? {
        return try {
            getPrefs(context).getString(KEY_ADMIN_EMAIL, null)
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "email failed: ${e.message}", e)
            null
        }
    }

    fun updateTokens(context: Context, accessToken: String?, refreshToken: String?, expiresAt: Long?) {
        try {
            val edit = getPrefs(context).edit()
            if (!accessToken.isNullOrEmpty()) edit.putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrEmpty()) edit.putString(KEY_REFRESH_TOKEN, refreshToken)
            if (expiresAt != null) edit.putLong(KEY_EXPIRES_AT, expiresAt)
            edit.apply()
        } catch (e: Exception) {
            android.util.Log.e("AdminSession", "updateTokens failed: ${e.message}", e)
        }
    }
}
