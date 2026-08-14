package com.naderai.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.naderai.smsreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var heartbeatManager: HeartbeatManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.saveButton.setOnClickListener { saveConfig() }
        binding.testButton.setOnClickListener { sendTestWebhook() }

        loadConfig()
        requestSmsPermission()
        updateStatus(false, "في انتظار الحفظ...")
    }

    override fun onResume() {
        super.onResume()
        startHeartbeat()
    }

    override fun onPause() {
        super.onPause()
        heartbeatManager?.stop()
    }

    private fun requestSmsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            val missing = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), SMS_PERMISSION_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "تم منح إذن SMS", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "مطلوب إذن SMS لقراءة رسائل التأكيد", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveConfig() {
        val webhookUrl = binding.webhookUrlInput.text.toString().trim()
        val secret = binding.secretInput.text.toString().trim()

        if (webhookUrl.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "رابط الـ webhook والسر مطلوبان", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_WEBHOOK_URL, webhookUrl)
            putString(KEY_SECRET, secret)
            apply()
        }
        Toast.makeText(this, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
        startHeartbeat()
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.webhookUrlInput.setText(prefs.getString(KEY_WEBHOOK_URL, "https://ccimllgqdxuvymdeikmn.supabase.co/functions/v1/wallet-auto-confirm"))
        binding.secretInput.setText(prefs.getString(KEY_SECRET, ""))
    }

    private fun startHeartbeat() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val webhookUrl = prefs.getString(KEY_WEBHOOK_URL, null) ?: return
        val secret = prefs.getString(KEY_SECRET, null) ?: return

        heartbeatManager?.stop()
        heartbeatManager = HeartbeatManager(this, webhookUrl, secret) { connected, message ->
            runOnUiThread { updateStatus(connected, message) }
        }
        heartbeatManager?.start()
    }

    private fun updateStatus(connected: Boolean, message: String) {
        binding.statusText.text = message
        val drawable = if (connected) R.drawable.status_online else R.drawable.status_offline
        binding.statusDot.setBackgroundResource(drawable)
    }

    private fun sendTestWebhook() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val webhookUrl = prefs.getString(KEY_WEBHOOK_URL, null) ?: return
        val secret = prefs.getString(KEY_SECRET, null) ?: return

        val testBody = mapOf(
            "sender_phone" to "01012345678",
            "message" to "لقد استلمت مبلغ 300.00 جنيه من رقم 01012345678",
            "received_at" to System.currentTimeMillis().toString()
        )

        WebhookSender.send(webhookUrl, secret, testBody) { success, message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SECRET = "webhook_secret"
        private const val SMS_PERMISSION_CODE = 101
    }
}
