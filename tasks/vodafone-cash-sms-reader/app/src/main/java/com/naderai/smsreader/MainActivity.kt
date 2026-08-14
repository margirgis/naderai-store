package com.naderai.smsreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.naderai.smsreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.saveButton.setOnClickListener { saveConfig() }
        binding.testButton.setOnClickListener { sendTestWebhook() }

        loadConfig()
        requestAllPermissions()
        updateStatus(false, "في انتظار الإعدادات...")
    }

    override fun onResume() {
        super.onResume()
        updateStatusFromPrefs()
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECEIVE_SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.READ_SMS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        }
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "تم منح جميع الصلاحيات ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "بعض الصلاحيات لم تُمنح — قد لا يعمل التطبيق بشكل صحيح", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveConfig() {
        val webhookUrl = binding.webhookUrlInput.text.toString()
            .trim().replace(" ", "").replace("\n", "").replace("\r", "")
        val secret = binding.secretInput.text.toString().trim()

        if (webhookUrl.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "رابط الـ webhook والسر مطلوبان", Toast.LENGTH_SHORT).show()
            return
        }
        if (!webhookUrl.startsWith("http://") && !webhookUrl.startsWith("https://")) {
            Toast.makeText(this, "الرابط يجب أن يبدأ بـ https://", Toast.LENGTH_SHORT).show()
            return
        }

        binding.webhookUrlInput.setText(webhookUrl)
        binding.secretInput.setText(secret)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_WEBHOOK_URL, webhookUrl)
            putString(KEY_SECRET, secret)
            apply()
        }

        Toast.makeText(this, "تم حفظ الإعدادات ✓", Toast.LENGTH_SHORT).show()
        updateStatus(false, "جاري الاتصال...")
        SmsMonitorService.start(this)
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.webhookUrlInput.setText(prefs.getString(KEY_WEBHOOK_URL,
            "https://ccimllgqdxuvymdeikmn.supabase.co/functions/v1/wallet-auto-confirm"))
        binding.secretInput.setText(prefs.getString(KEY_SECRET, ""))
    }

    private fun updateStatusFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasConfig = !prefs.getString(KEY_WEBHOOK_URL, null).isNullOrEmpty() &&
                        !prefs.getString(KEY_SECRET, null).isNullOrEmpty()
        if (hasConfig) {
            updateStatus(false, "الخدمة تعمل في الخلفية...")
            SmsMonitorService.start(this)
        }
    }

    private fun sendTestWebhook() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val webhookUrl = prefs.getString(KEY_WEBHOOK_URL, null)?.trim()
        val secret = prefs.getString(KEY_SECRET, null)?.trim()

        if (webhookUrl.isNullOrEmpty() || secret.isNullOrEmpty()) {
            Toast.makeText(this, "احفظ الإعدادات أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus(false, "جاري الاختبار...")
        val testBody = mapOf(
            "action" to "heartbeat",
            "device_id" to HeartbeatManager.getDeviceId(this),
            "device_model" to (Build.MODEL ?: "Unknown"),
            "device_name" to (Build.DEVICE ?: "Unknown"),
            "app_version" to "1.0.2"
        )

        WebhookSender.send(webhookUrl, secret, testBody) { success, message ->
            runOnUiThread {
                updateStatus(success, if (success) "متصل ✓" else "غير متصل: $message")
                Toast.makeText(this, if (success) "✓ الاتصال بالسيرفر شغال" else "✗ $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus(connected: Boolean, message: String) {
        binding.statusText.text = message
        binding.statusDot.setBackgroundResource(if (connected) R.drawable.status_online else R.drawable.status_offline)
    }

    companion object {
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SECRET = "webhook_secret"
        private const val PERM_REQUEST_CODE = 101
    }
}
