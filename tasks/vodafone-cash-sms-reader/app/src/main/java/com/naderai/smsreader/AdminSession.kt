package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * يدير جلسة تسجيل دخول الأدمن في التطبيق.
 * يتم تخزين التوكنات بشكل مشفّر باستخدام AndroidX Security.
 */
object AdminSession {

    private const val PREFS_FILE = "admin_session_encrypted"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_ADMIN_EMAIL = "admin_email"
    private const val KEY_LOGGED_IN = "logged_in"

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
        getPrefs(context).edit().clear().apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_LOGGED_IN, false)) return false
        // نعتبر الجلسة موجودة إذا كان لدينا access token أو refresh token،
        // لأن Edge Function يمكنه تجديد التوكن إذا انتهت صلاحيته.
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty() ||
                !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty()
    }

    fun accessToken(context: Context): String? = getPrefs(context).getString(KEY_ACCESS_TOKEN, null)
    fun refreshToken(context: Context): String? = getPrefs(context).getString(KEY_REFRESH_TOKEN, null)
    fun email(context: Context): String? = getPrefs(context).getString(KEY_ADMIN_EMAIL, null)

    fun updateTokens(context: Context, accessToken: String?, refreshToken: String?, expiresAt: Long?) {
        val edit = getPrefs(context).edit()
        if (!accessToken.isNullOrEmpty()) edit.putString(KEY_ACCESS_TOKEN, accessToken)
        if (!refreshToken.isNullOrEmpty()) edit.putString(KEY_REFRESH_TOKEN, refreshToken)
        if (expiresAt != null) edit.putLong(KEY_EXPIRES_AT, expiresAt)
        edit.apply()
    }
}
