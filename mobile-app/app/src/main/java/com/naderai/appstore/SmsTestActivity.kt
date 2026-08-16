package com.naderai.appstore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.naderai.appstore.databinding.ActivitySmsTestBinding
import com.naderai.appstore.sms.TaskScanner

/**
 * شاشة اختبار قراءة رسائل Vodafone Cash.
 * تتيح للمسؤول التحقق من أن نظام القراءة يعمل صحيحاً قبل نشر التطبيق.
 */
class SmsTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshStatus()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnParseSms.setOnClickListener {
            val text = binding.etSmsText.text.toString().trim()
            if (text.isEmpty()) {
                binding.txtParseResult.text = "⚠️ الرجاء إدخال نص الرسالة"
                binding.txtParseResult.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val isOfficial = TaskScanner.isOfficialVodafoneCashMessage(text)
            val parsed = TaskScanner.testParseSms(text)
            val result = buildString {
                appendLine("هل رسالة رسمية: ${if (isOfficial) "✅ نعم" else "❌ لا"}")
                appendLine("المبلغ: ${parsed.amount?.let { "%.2f جنيه".format(it) } ?: "غير موجود"}")
                appendLine("رقم المرسل: ${parsed.senderPhone ?: "غير موجود"}")
                appendLine("اسم المرسل: ${parsed.senderName ?: "غير موجود"}")
                appendLine("رقم العملية: ${parsed.transactionId ?: "غير موجود"}")
                appendLine("محفظة المستلم: ${parsed.receiverWallet ?: "غير موجود"}")
            }
            binding.txtParseResult.text = result
            binding.txtParseResult.visibility = View.VISIBLE
        }

        binding.btnScanInbox.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                binding.txtScanResult.text = "❌ صلاحية قراءة الرسائل غير ممنوحة"
                binding.txtScanResult.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val messages = TaskScanner.scanInboxForTest(this)
            val result = if (messages.isEmpty()) {
                "لم يتم العثور على رسائل فودافون كاش في آخر 50 رسالة"
            } else {
                buildString {
                    appendLine("✅ وُجد ${messages.size} رسالة فودافون كاش:\n")
                    messages.forEachIndexed { i, sms ->
                        appendLine("── رسالة ${i + 1} ──")
                        appendLine("المبلغ: ${sms.amount?.let { "%.2f جنيه".format(it) } ?: "—"}")
                        appendLine("رقم المرسل: ${sms.senderPhone ?: "—"}")
                        appendLine("اسم المرسل: ${sms.senderName ?: "—"}")
                        appendLine("رقم العملية: ${sms.transactionId ?: "—"}")
                        appendLine()
                    }
                }
            }
            binding.txtScanResult.text = result
            binding.txtScanResult.visibility = View.VISIBLE
        }
    }

    private fun refreshStatus() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReceive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        binding.txtStatus.text = buildString {
            appendLine("إصدار التطبيق: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("صلاحية قراءة SMS: ${if (hasSms) "✅ ممنوحة" else "❌ مرفوضة"}")
            appendLine("صلاحية استقبال SMS: ${if (hasReceive) "✅ ممنوحة" else "❌ مرفوضة"}")
            appendLine("الجهاز: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
        }
    }
}
