package com.naderai.smsreader

/**
 * مساعدات إعداد الاتصال بـ Supabase.
 *
 * التطبيق بساطة: يلزم المستخدم من إدخال رابط Supabase الاساسي + Anon Key.
 * نبني رابط الـ Edge Function الكامل من الرابط الاساسي.
 */
object SupabaseConfig {

    private const val EDGE_FUNCTION_PATH = "/functions/v1/wallet-auto-confirm"
    private const val ADMIN_LOGIN_PATH = "/functions/v1/admin-login"
    private const val ADMIN_ORDERS_PATH = "/functions/v1/admin-orders"
    private const val ADMIN_TASK_RESULT_PATH = "/functions/v1/admin-task-result"
    private const val ADMIN_MANUAL_CONFIRM_PATH = "/functions/v1/admin-manual-confirm"
    private const val ADMIN_REOPEN_ORDER_PATH   = "/functions/v1/admin-reopen-order"
    private const val ADMIN_OPEN_CASE_PATH      = "/functions/v1/admin-open-case"

    /** يقبل رابط Supabase الاساسي ويرجع رابط الـ Edge Function الكامل */
    fun getWebhookUrl(supabaseUrl: String?): String? = buildUrl(supabaseUrl, EDGE_FUNCTION_PATH)

    /** رابط تسجيل دخول الأدمن */
    fun getAdminLoginUrl(supabaseUrl: String?): String? = buildUrl(supabaseUrl, ADMIN_LOGIN_PATH)

    /** رابط جلب كل الطلبات للأدمن */
    fun getAdminOrdersUrl(supabaseUrl: String?): String? = buildUrl(supabaseUrl, ADMIN_ORDERS_PATH)

    /** رابط إرسال نتيجة فحص SMS من الأدمن دون الحاجة لـ Webhook Secret */
    fun getAdminTaskResultUrl(supabaseUrl: String?): String? = buildUrl(supabaseUrl, ADMIN_TASK_RESULT_PATH)
    fun getAdminManualConfirmUrl(supabaseUrl: String?): String? = buildUrl(supabaseUrl, ADMIN_MANUAL_CONFIRM_PATH)
    fun getAdminReopenOrderUrl(supabaseUrl: String?): String?   = buildUrl(supabaseUrl, ADMIN_REOPEN_ORDER_PATH)
    fun getAdminOpenCaseUrl(supabaseUrl: String?): String?      = buildUrl(supabaseUrl, ADMIN_OPEN_CASE_PATH)

    /** رابط جلب كل الطلبات للأدمن (alias) */
    fun getAdminUrl(supabaseUrl: String?): String? = getAdminOrdersUrl(supabaseUrl)

    private fun buildUrl(supabaseUrl: String?, path: String): String? {
        val base = supabaseUrl?.trim() ?: return null
        if (base.isEmpty()) return null
        // إذا كان المستخدم قد وضع الرابط الكامل في الإعدادات القديمة، ننظف المسار
        return if (base.endsWith(path)) base else base.trimEnd('/').plus(path)
    }
}

// New Edge Function paths for order lifecycle v2
// (added by migration 00048)
