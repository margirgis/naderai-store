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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val hasConfig = !prefs.getString(KEY_WEBHOOK_URL, null).isNullOrEmpty() &&
                !prefs.getString(KEY_SECRET, null).isNullOrEmpty()
        if (hasConfig) SmsMonitorService.start(this)
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
