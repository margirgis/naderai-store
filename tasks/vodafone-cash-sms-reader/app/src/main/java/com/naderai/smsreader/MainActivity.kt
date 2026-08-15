package com.naderai.smsreader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayoutMediator
import com.naderai.smsreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MainPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = adapter.getTitle(position)
        }.attach()

        requestAllPermissions()
        startServiceIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        startServiceIfConfigured()
    }

    private fun startServiceIfConfigured() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasConfig = !prefs.getString(KEY_WEBHOOK_URL, null).isNullOrEmpty() &&
                !prefs.getString(KEY_SECRET, null).isNullOrEmpty()
        if (hasConfig) SmsMonitorService.start(this)
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECEIVE_SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.READ_SMS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST_CODE)
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                Toast.makeText(this, "تم منح جميع الصلاحيات ✓", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(this, "بعض الصلاحيات لم تُمنح — قد لا يعمل التطبيق بشكل صحيح", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SECRET = "webhook_secret"
        private const val PERM_REQUEST_CODE = 101
    }
}
