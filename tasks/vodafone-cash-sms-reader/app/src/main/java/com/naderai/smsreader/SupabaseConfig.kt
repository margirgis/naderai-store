package com.naderai.smsreader

/**
 * مساعدات إعداد الاتصال بـ Supabase.
 *
 * التطبيق بساطة: يلزم المستخدم من إدخال رابط Supabase الاساسي + Anon Key.
 * نبني رابط الـ Edge Function الكامل من الرابط الاساسي.
 */
object SupabaseConfig {

    private const val EDGE_FUNCTION_PATH = "/functions/v1/wallet-auto-confirm"

    /** يقبل رابط Supabase الاساسي ويرجع رابط الـ Edge Function الكامل */
    fun getWebhookUrl(supabaseUrl: String?): String? {
        val base = supabaseUrl?.trim() ?: return null
        if (base.isEmpty()) return null
        // إذا كان المستخدم قد وضع الرابط الكامل في الإعدادات القديمة، ننظف المسار
        return if (base.endsWith(EDGE_FUNCTION_PATH)) base else base.trimEnd('/').plus(EDGE_FUNCTION_PATH)
    }
}
