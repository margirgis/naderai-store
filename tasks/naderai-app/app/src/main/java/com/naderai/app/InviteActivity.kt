package com.naderai.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.naderai.app.databinding.ActivityInviteBinding

/**
 * شاشة رابط الدعوة الاختيارية.
 * تظهر بعد صفحة الصلاحيات مباشرةً وتسمح للمستخدم بإدخال رابط دعوة،
 * ثم تفتح الموقع داخل WebView بحيث يُحتسب الرابط للداعي.
 */
class InviteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInviteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnContinue.setOnClickListener { applyInviteAndContinue() }
        binding.btnSkip.setOnClickListener { openMain(null) }
    }

    private fun applyInviteAndContinue() {
        val input = binding.etInviteLink.text.toString().trim()

        if (input.isEmpty()) {
            // لم يُدخِل رابط، نعامله كتخطي
            openMain(null)
            return
        }

        // التحقق من صحة الرابط
        val fixed = fixInviteUrl(input)
        if (fixed == null) {
            Toast.makeText(this, getString(R.string.invite_error_invalid), Toast.LENGTH_LONG).show()
            return
        }

        // فتح الرابط داخل WebView من خلال MainActivity
        openMain(fixed)
    }

    /**
     * يصحح روابط الدعوة غير المكتملة:
     * - إذا كان المستخدم لصق فقط `?invitecode=user-xxx` نضيف له النطاق.
     * - نضمن وجود https://.
     * - نرفض أي رابط لا يخص النطاق المدعوم.
     */
    private fun fixInviteUrl(input: String): String? {
        val trimmed = input.trim()

        // رابط كامل صحيح
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return if (isSupportedDomain(trimmed)) trimmed else null
        }

        // مجرد invitecode query string
        if (trimmed.startsWith("?invitecode=")) {
            return "${MainActivity.APP_URL}/$trimmed"
        }

        // رابط نسبي بدون https
        if (trimmed.startsWith("appmedo.com", ignoreCase = true) ||
            trimmed.startsWith("www.appmedo.com", ignoreCase = true) ||
            trimmed.startsWith("medo.dev", ignoreCase = true) ||
            trimmed.startsWith("www.medo.dev", ignoreCase = true)
        ) {
            return "https://$trimmed"
        }

        return null
    }

    private fun isSupportedDomain(url: String): Boolean {
        return url.contains("appmedo.com", ignoreCase = true) ||
            url.contains("medo.dev", ignoreCase = true) ||
            url.contains("ccimllgqdxuvymdeikmn.supabase.co", ignoreCase = true)
    }

    private fun openMain(url: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (!url.isNullOrEmpty()) {
                putExtra(MainActivity.EXTRA_URL, url)
            }
        }
        startActivity(intent)
        finish()
    }
}
