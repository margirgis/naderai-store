package com.naderai.smsreader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.naderai.smsreader.BuildConfig
import com.naderai.smsreader.databinding.ActivityMainBinding
import androidx.lifecycle.Observer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MainPagerAdapter
    private var orderSyncManager: OrderSyncManager? = null

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

        loadOrdersFromStorage()
        AppState.orders.observe(this, Observer { orders ->
            OrderStorage.saveOrders(this, orders)
        })
        // ── مراقبة التحديث الإجباري ───────────────────────────────────────────
        AppState.forceUpdateRequired.observe(this, Observer { required ->
            if (required == true) showForceUpdateDialog()
        })
        startServiceIfConfigured()
        startOrderSyncManager()
        NetworkMonitor.start(this)
        SyncTriggers.onAppStart(this)
    }

    private fun loadOrdersFromStorage() {
        try {
            val cached = OrderStorage.loadOrders(this)
            if (cached.isNotEmpty()) {
                AppState.setOrders(cached)
                android.util.Log.d("MainActivity", "Loaded ${cached.size} orders from local storage")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to load local orders: ${e.message}")
        }
    }

    private fun startOrderSyncManager() {
        // Bug #1 Fix: لا تُنشئ OrderSyncManager هنا — SmsMonitorService + SyncTriggers يُديرانه
        // إنشاء 3 instances منفصلة كان يُسبب 3×sync كل 10s → GENERIC_ERROR chain
        // SyncTriggers.onAppStart() و SmsMonitorService.onStartCommand() يتولى ذلك
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsHelper.allPermissionsGranted(this)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }
        startServiceIfConfigured()
        startOrderSyncManager()
        NetworkMonitor.start(this)
        SyncTriggers.triggerSync(this, "resume")
    }

    override fun onPause() {
        super.onPause()
        NetworkMonitor.stop()
    }

    /** حوار التحديث الإجباري — يمنع استخدام التطبيق حتى يتم التحديث */
    private var forceUpdateDialog: AlertDialog? = null
    private fun showForceUpdateDialog() {
        if (forceUpdateDialog?.isShowing == true) return
        forceUpdateDialog = AlertDialog.Builder(this)
            .setTitle("⚠️ تحديث إجباري مطلوب")
            .setMessage(
                "إصدار التطبيق الحالي (v${BuildConfig.VERSION_NAME}) قديم ويحتوي على مشاكل.\n\n" +
                "يجب تحديث التطبيق للإصدار الأحدث من GitHub Releases حتى يعمل بشكل صحيح."
            )
            .setCancelable(false)
            .setPositiveButton("فتح رابط التحديث") { _, _ ->
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/naderai/sms-reader/releases/latest")
                )
                try { startActivity(intent) } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Cannot open browser: ${e.message}")
                }
                // نعيد عرض الحوار بعد 3 ثوانٍ لأن المستخدم لم يثبّت التحديث بعد
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    AppState.forceUpdateRequired.value?.let { if (it) showForceUpdateDialog() }
                }, 3_000)
            }
            .show()
    }

    private fun startServiceIfConfigured() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val hasWebhook = !prefs.getString(KEY_WEBHOOK_URL, null).isNullOrEmpty() &&
                    !prefs.getString(KEY_SECRET, null).isNullOrEmpty()
            val adminLoggedIn = AdminSession.isLoggedIn(this)
            if (hasWebhook || adminLoggedIn) SmsMonitorService.start(this)
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
