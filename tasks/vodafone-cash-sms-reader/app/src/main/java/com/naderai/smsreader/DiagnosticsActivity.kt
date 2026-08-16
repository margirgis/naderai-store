package com.naderai.smsreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.naderai.smsreader.databinding.ActivityDiagnosticsBinding

/**
 * شاشة تشخيصات تعرض حالة الفحص والطلبات والأذونات والسيرفر.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "تشخيصات النظام"

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnOpenSettings.setOnClickListener { openAppSettings() }

        refresh()
    }

    private fun refresh() {
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReceive = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = isBatteryOptimizationIgnored(this)
        val serviceRunning = SmsMonitorService.isRunning

        binding.statusPermissions.text = if (hasSms && hasReceive) "✅ ممنوحة" else "❌ ناقصة"
        binding.statusBattery.text = if (batteryOk) "✅ متجاهلة" else "❌ مش متجاهلة"
        binding.statusService.text = if (serviceRunning) "✅ شغال" else "❌ متوقف"
        binding.statusPendingOrders.text = AppState.getOrders().filter { it.status == OrderStatus.PENDING }.size.toString()
        binding.statusLastScan.text = AppState.getOrders().maxByOrNull { it.updatedAt }?.let { "#${it.orderNumber ?: it.requestId.take(8)} — ${it.status.label}" } ?: "—"
        binding.statusConnection.text = AppState.getConnectionStatus()

        binding.diagnosticText.visibility = View.VISIBLE
        binding.diagnosticText.text = buildString {
            appendLine("آخر 3 إشعارات:")
            AppState.getNotifications().take(3).forEach { n ->
                appendLine("• ${n.title}: ${n.message}")
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "مش قادر أفتح الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
