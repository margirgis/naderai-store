package com.naderai.smsreader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.naderai.smsreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MainPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        // Catch-all to prevent crash loops and let the user see diagnostics
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("GlobalException", "Crash on ${thread.name}", throwable)
            AppState.lastError.postValue("تعطل: ${throwable.message}")
        }
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to inflate main activity", e)
            AppState.lastError.postValue("فشل فتح التطبيق: ${e.message}")
            // Show a simple fallback instead of crashing
            val tv = android.widget.TextView(this)
            tv.text = "تعذر فتح التطبيق. يرجى إعادة التثبيت أو مراجعة الإعدادات.\n\n${e.message}"
            tv.setPadding(32, 32, 32, 32)
            setContentView(tv)
            return
        }

        if (!PermissionsHelper.allPermissionsGranted(this)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 3

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = adapter.getTitle(position)
        }.attach()

        startServiceIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsHelper.allPermissionsGranted(this)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }
        startServiceIfConfigured()
    }

    private fun startServiceIfConfigured() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val hasConfig = !prefs.getString(KEY_WEBHOOK_URL, null).isNullOrEmpty() &&
                    !prefs.getString(KEY_SECRET, null).isNullOrEmpty()
            if (hasConfig) SmsMonitorService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start service", e)
            AppState.lastError.postValue("فشل تشغيل الخدمة: ${e.message}")
        }
    }

    /** الرابط الكامل لـ Edge Function من رابط Supabase الاساسي */
    fun getWebhookUrl(): String? = SupabaseConfig.getWebhookUrl(
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_WEBHOOK_URL, null)
    )

    companion object {
        const val PREFS_NAME = "naderai_sms_reader"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SECRET = "webhook_secret"
    }
}
