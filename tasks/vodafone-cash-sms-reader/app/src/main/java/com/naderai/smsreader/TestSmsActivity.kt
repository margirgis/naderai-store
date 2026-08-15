package com.naderai.smsreader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.naderai.smsreader.databinding.ActivityTestSmsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة تجربة فحص رسالة فودافون كاش.
 * تسمح للمستخدم بلصق رسالة حقيقية والتحقق مما يستخرجه التطبيق،
 * أو بقراءة صندوق الرسائل الحقيقي للتأكد إن التطبيق بيقرأ الرسائل.
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
        binding.btnScanInbox.setOnClickListener { scanRealInbox() }
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

        // التطابق التام يحتاج صيغة فودافون كاش الرسمية + استخراج كل البيانات
        val isMatch = TaskScanner.isOfficialVodafoneCashMessage(text) &&
                parsed.amount != null &&
                parsed.senderPhone != null &&
                parsed.transactionId != null

        binding.matchBadge.also { badge ->
            badge.visibility = View.VISIBLE
            if (isMatch) {
                badge.text = "✅ تم التطابق — الرسالة فودافون كاش رسمية"
                badge.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, theme))
                badge.setTextColor(resources.getColor(android.R.color.white, theme))
            } else {
                badge.text = "❌ لا توجد مطابقة تامة — الرسالة مش فودافون كاش أو نقص بيانات"
                badge.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, theme))
                badge.setTextColor(resources.getColor(android.R.color.white, theme))
            }
        }
    }

    private fun scanRealInbox() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "مفيش إذن قراءة الرسائل — اديه من إعدادات الصلاحيات", Toast.LENGTH_LONG).show()
            return
        }

        binding.inboxResultTitle.visibility = View.VISIBLE
        binding.inboxResultText.text = "⏳ بيقرأ صندوق الرسائل..."
        binding.inboxResultText.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val messages = TaskScanner.scanInboxForTest(this@TestSmsActivity)
            val text = when {
                messages.isEmpty() -> "📭 مفيش رسائل فودافون كاش في الجهاز.\n\nتأكد إن:\n• في رسالة واصلة من فودافون كاش.\n• تم منح إذن قراءة الرسائل."
                else -> messages.take(5).mapIndexed { index, sms ->
                    val official = TaskScanner.isOfficialVodafoneCashMessage(sms.body)
                    """${index + 1}. ${if (official) "✅" else "⚠️"} ${sms.body.take(60)}…
المبلغ: ${sms.amount ?: "—"} | المحوّل: ${sms.senderPhone ?: "—"} | العملية: ${sms.transactionId ?: "—"}"""
                }.joinToString("\n\n---\n\n")
            }
            withContext(Dispatchers.Main) {
                binding.inboxResultText.text = text
            }
        }
    }
}
