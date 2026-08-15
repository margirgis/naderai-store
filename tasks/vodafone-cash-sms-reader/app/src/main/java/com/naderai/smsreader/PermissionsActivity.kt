package com.naderai.smsreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * شاشة صلاحيات إجبارية — التطبيق لا يعمل بدونها.
 * مطلوب: قراءة الرسائل، استقبال الرسائل، الإشعارات، تشغيل الخدمة في الخلفية، وتجاهل تحسين البطارية.
 */
class PermissionsActivity : AppCompatActivity() {

    private val permissions = mutableListOf<String>().apply {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (allPermissionsGranted()) {
            startMainActivity()
            return
        }
        setContentView(R.layout.activity_permissions)
        findViewById<android.widget.Button>(R.id.btnGrantPermissions).setOnClickListener {
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && isBatteryOptimizationIgnored()) {
            startMainActivity()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestAllPermissions() {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        } else {
            requestBatteryOptimizationExemption()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryOptimizationIgnored()) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                    BATTERY_REQUEST_CODE
                )
            } catch (_: Exception) {
                showMandatoryDialog()
            }
        } else {
            startMainActivity()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "تم منح جميع الصلاحيات ✓", Toast.LENGTH_SHORT).show()
                requestBatteryOptimizationExemption()
            } else {
                val permanentlyDenied = permissions.filterIndexed { index, perm ->
                    grantResults[index] == PackageManager.PERMISSION_DENIED &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
                }
                if (permanentlyDenied.isNotEmpty()) {
                    showSettingsDialog(permanentlyDenied)
                } else {
                    showMandatoryDialog()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BATTERY_REQUEST_CODE) {
            if (allPermissionsGranted() && isBatteryOptimizationIgnored()) {
                startMainActivity()
            } else {
                showMandatoryDialog()
            }
        }
    }

    private fun showMandatoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("صلاحيات إجبارية")
            .setMessage("التطبيق يحتاج لصلاحيات الرسائل والإشعارات والبطارية عشان يقدر يفحص الرسائل. لو ماتدتهاش التطبيق مش هيشتغل.")
            .setCancelable(false)
            .setPositiveButton("إعادة المحاولة") { _, _ -> requestAllPermissions() }
            .setNegativeButton("إغلاق التطبيق") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showSettingsDialog(permanentlyDenied: List<String>) {
        val names = permanentlyDenied.map { permissionName(it) }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("محتاجين نفتح الإعدادات")
            .setMessage("الصلاحيات دي اتقفلت نهائياً:\n$names\n\nافتح الإعدادات وادّيها يدوياً.")
            .setCancelable(false)
            .setPositiveButton("فتح الإعدادات") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton("إغلاق التطبيق") { _, _ -> finishAffinity() }
            .show()
    }

    private fun permissionName(perm: String): String = when (perm) {
        Manifest.permission.READ_SMS -> "قراءة الرسائل"
        Manifest.permission.RECEIVE_SMS -> "استقبال الرسائل"
        Manifest.permission.POST_NOTIFICATIONS -> "إظهار الإشعارات"
        Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC -> "تشغيل الخدمة في الخلفية"
        else -> perm
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        private const val PERM_REQUEST_CODE = 101
        private const val BATTERY_REQUEST_CODE = 102
    }
}
