package com.naderai.smsreader

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مساعد لتسجيل الأعطال في ملف داخل مجلد التطبيق.
 * الملف ده بيسهل معرفة سبب التعطل لو مفيش ADB متاح.
 */
object CrashLog {

    private const val CRASH_FILE = "last_crash.txt"

    fun write(context: Context, throwable: Throwable) {
        try {
            val file = File(context.cacheDir, CRASH_FILE)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val stackTrace = throwable.stackTraceToString()
            file.writeText("[$timestamp] ${throwable.javaClass.simpleName}: ${throwable.message}\n\n$stackTrace")
        } catch (e: Exception) {
            android.util.Log.e("CrashLog", "Failed to write crash log: ${e.message}")
        }
    }

    fun read(context: Context): String? {
        return try {
            val file = File(context.cacheDir, CRASH_FILE)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.cacheDir, CRASH_FILE).delete()
        } catch (_: Exception) {}
    }
}
