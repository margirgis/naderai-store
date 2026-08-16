package com.naderai.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.naderai.app.databinding.ActivityPermissionsBinding

/**
 * أول شاشة تظهر عند فتح التطبيق.
 * تطلب صلاحيات SMS + Notifications + Battery قبل فتح WebView.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding

    companion object {
        private const val REQ_SMS = 101
        private const val REQ_NOTIF = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGrantSms.setOnClickListener { requestSms() }
        binding.btnGrantNotif.setOnClickListener { requestNotifications() }
        binding.btnGrantBattery.setOnClickListener { requestBatteryOptimization() }
        binding.btnContinue.setOnClickListener { proceed() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val smsOk = hasSmsPermission()
        val notifOk = hasNotifPermission()
        val battOk = isBatteryOptimizationIgnored()

        binding.txtSmsStatus.text = if (smsOk) "✅" else "❌"
        binding.txtNotifStatus.text = if (notifOk) "✅" else "⏳"
        binding.txtBatteryStatus.text = if (battOk) "✅" else "⏳"

        binding.btnGrantSms.visibility = if (smsOk) View.GONE else View.VISIBLE
        binding.btnGrantNotif.visibility = if (notifOk) View.GONE else View.VISIBLE
        binding.btnGrantBattery.visibility = if (battOk) View.GONE else View.VISIBLE

        // يسمح بالمتابعة لو على الأقل SMS ممنوح
        binding.btnContinue.isEnabled = smsOk
        binding.btnContinue.alpha = if (smsOk) 1f else 0.5f
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestSms() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            REQ_SMS
        )
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // بعض الأجهزة لا تدعم هذا الطلب المباشر — افتح إعدادات البطارية
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun proceed() {
        startActivity(Intent(this, InviteActivity::class.java))
        finish()
    }
}
