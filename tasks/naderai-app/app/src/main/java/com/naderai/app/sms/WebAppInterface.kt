package com.naderai.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.naderai.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript Bridge — يكشف واجهة "NaderAI" لصفحات الموقع.
 *
 * يمكن للصفحة استدعاء:
 *   NaderAI.getDeviceId()       → String: Device ID الجهاز
 *   NaderAI.getAppVersion()     → String: إصدار التطبيق
 *   NaderAI.hasSmsPermission()  → Boolean: هل صلاحية SMS ممنوحة؟
 *   NaderAI.scanRecentSms(n)    → JSON: آخر n رسالة Vodafone Cash محللة
 *   NaderAI.getDeviceInfo()     → JSON: معلومات الجهاز كاملة
 *   NaderAI.isServiceRunning()  → Boolean: هل الخدمة تعمل؟
 *
 * ملاحظة: جميع الدوال تُنفذ في main thread — لا حاجة لـ runOnUiThread.
 */
class WebAppInterface(private val context: Context) {

    /** Device ID مستقر بين التحديثات */
    @JavascriptInterface
    fun getDeviceId(): String = DeviceInfo.getDeviceId(context)

    /** إصدار التطبيق */
    @JavascriptInterface
    fun getAppVersion(): String = BuildConfig.VERSION_NAME

    /** هل صلاحية قراءة SMS ممنوحة؟ */
    @JavascriptInterface
    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * يفحص آخر [count] رسالة في صندوق الوارد ويُعيد رسائل Vodafone Cash محللة بصيغة JSON.
     * مثال الاستدعاء من JS:
     *   const msgs = JSON.parse(NaderAI.scanRecentSms(50));
     */
    @JavascriptInterface
    fun scanRecentSms(count: Int = 30): String {
        if (!hasSmsPermission()) return buildError("SMS permission not granted")
        val messages = TaskScanner.scanInboxForTest(context, count)
        val arr = JSONArray()
        messages.forEach { sms ->
            arr.put(JSONObject().apply {
                put("amount", sms.amount ?: JSONObject.NULL)
                put("senderPhone", sms.senderPhone ?: JSONObject.NULL)
                put("senderName", sms.senderName ?: JSONObject.NULL)
                put("transactionId", sms.transactionId ?: JSONObject.NULL)
                put("receiverWallet", sms.receiverWallet ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("count", messages.size)
            put("messages", arr)
        }.toString()
    }

    /**
     * يُعيد معلومات الجهاز كاملة بصيغة JSON.
     * مثال من JS:
     *   const info = JSON.parse(NaderAI.getDeviceInfo());
     */
    @JavascriptInterface
    fun getDeviceInfo(): String = JSONObject().apply {
        put("deviceId", DeviceInfo.getDeviceId(context))
        put("model", DeviceInfo.getModel())
        put("androidVersion", DeviceInfo.getAndroidVersion())
        put("appVersion", BuildConfig.VERSION_NAME)
        put("platform", "android")
        put("hasSmsPermission", hasSmsPermission())
    }.toString()

    /** هل الخدمة تعمل في الخلفية؟ (يُفترض دائماً true بعد منح الصلاحيات) */
    @JavascriptInterface
    fun isServiceRunning(): Boolean = true

    /**
     * استقبال أمر من الصفحة لفحص SMS بالأمر المباشر.
     * يُرسل "scan_now" للخدمة.
     */
    @JavascriptInterface
    fun triggerSmsScan(): String {
        SmsReaderService.start(context)
        return "{\"ok\":true,\"message\":\"scan triggered\"}"
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private fun buildError(msg: String) = JSONObject().apply {
        put("ok", false)
        put("error", msg)
    }.toString()
}
