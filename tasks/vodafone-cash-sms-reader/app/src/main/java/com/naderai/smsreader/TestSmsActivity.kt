package com.naderai.smsreader

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.naderai.smsreader.databinding.ActivityTestSmsBinding

/**
 * شاشة تجربة فحص رسالة فودافون كاش.
 * تسمح للمستخدم بلصق رسالة حقيقية والتحقق مما يستخرجه التطبيق.
 */
class TestSmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestSmsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestSmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "تجربة فحص الرسالة"

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnSample.setOnClickListener { loadSample() }
        binding.btnScan.setOnClickListener { scanMessage() }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "المفكرة فارغة", Toast.LENGTH_SHORT).show()
        } else {
            binding.smsInput.setText(text)
        }
    }

    private fun loadSample() {
        binding.smsInput.setText(
            "تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل بإسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13. رصيدك الحالي: 83946.14 جنيه رقم العملية: 022655099780"
        )
    }

    private fun scanMessage() {
        val text = binding.smsInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            Toast.makeText(this, "الصق رسالة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        // نستخدم نفس الـ parser الموجود في TaskScanner
        val parsed = TaskScanner.testParseSms(text)
        val result = buildString {
            appendLine("المبلغ: ${parsed.amount?.toString() ?: "—"}")
            appendLine("رقم المحوّل: ${parsed.senderPhone ?: "—"}")
            appendLine("اسم المحوّل: ${parsed.senderName ?: "—"}")
            appendLine("رقم المحفظة المستقبلة: ${parsed.receiverWallet ?: "—"}")
            appendLine("رقم العملية: ${parsed.transactionId ?: "—"}")
            appendLine("النص الأصلي: ${parsed.body.take(80)}…")
        }

        binding.resultText.text = result
        binding.resultTitle.visibility = View.VISIBLE
        binding.resultText.visibility = View.VISIBLE

        val isMatch = parsed.amount != null && parsed.senderPhone != null && parsed.transactionId != null
        binding.matchBadge.also { badge ->
            badge.visibility = View.VISIBLE
            if (isMatch) {
                badge.text = "✅ تم التطابق — الرسالة مقروءة"
                badge.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, theme))
                badge.setTextColor(resources.getColor(android.R.color.white, theme))
            } else {
                badge.text = "❌ لا توجد مطابقة تامة"
                badge.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, theme))
                badge.setTextColor(resources.getColor(android.R.color.white, theme))
            }
        }
    }
}
