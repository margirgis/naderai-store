package com.naderai.smsreader
import com.naderai.smsreader.BuildConfig

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.naderai.smsreader.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadConfig()
        showDeviceStatus()

        binding.saveButton.setOnClickListener { saveConfig() }
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.registerButton.setOnClickListener { forceRegister() }
        binding.clearQueueButton.setOnClickListener { clearRetryQueue() }
    }

    private fun loadConfig() {
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, 0)
        // المستخدم يدخل رابط Supabase الاساسي فقط — نبني الرابط الكامل لـ Edge Function تلقائياً
        binding.webhookUrlInput.setText(prefs.getString(MainActivity.KEY_WEBHOOK_URL,
            "https://ccimllgqdxuvymdeikmn.supabase.co"))
        binding.secretInput.setText(prefs.getString(MainActivity.KEY_SECRET,
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNjaW1sbGdxZHh1dnltZGVpa21uIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY2ODk3OTQsImV4cCI6MjEwMjI2NTc5NH0.intP2QkhXHswRigBpCYb127yNk3VAfj68rpS_Ujvies"))
    }

    private fun showDeviceStatus() {
        val ctx = requireContext()
        binding.deviceIdValue.text = HeartbeatManager.getDeviceId(ctx)
        binding.deviceModelValue.text = Build.MODEL ?: "—"
        binding.androidVersionValue.text = Build.VERSION.RELEASE ?: "—"
        binding.appVersionValue.text = BuildConfig.VERSION_NAME
        binding.retryQueueSize.text = "الطابور: ${RetryQueue.size(ctx)} عنصر"

        AppState.isConnected.observe(viewLifecycleOwner) { connected ->
            updateConnectionLabel(connected, AppState.isRegistered.value)
        }
        AppState.isRegistered.observe(viewLifecycleOwner) { registered ->
            updateConnectionLabel(AppState.isConnected.value, registered)
        }
        AppState.lastSyncTime.observe(viewLifecycleOwner) { ts ->
            binding.lastSyncValue.text = if (ts != null)
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))
            else "—"
        }
        AppState.lastError.observe(viewLifecycleOwner) { err ->
            binding.lastErrorValue.text = err ?: "—"
        }
    }

    private fun updateConnectionLabel(connected: Boolean?, registered: Boolean?) {
        binding.connectionStatusValue.text = when {
            connected == true && registered == true -> "🟢 متصل ومسجل"
            connected == true && registered != true -> "🟡 متصل — غير مسجل"
            else -> "🔴 غير متصل"
        }
    }

    private fun saveConfig() {
        val rawUrl = binding.webhookUrlInput.text.toString().trim()
        val secret = binding.secretInput.text.toString().trim()
        if (rawUrl.isEmpty() || secret.isEmpty()) {
            Toast.makeText(requireContext(), "رابط Supabase و Anon Key مطلوبان", Toast.LENGTH_SHORT).show()
            return
        }
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
        if (webhookUrl.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "رابط Supabase غير صالح — يجب أن يبدأ بـ https://", Toast.LENGTH_SHORT).show()
            return
        }
        if (!secret.startsWith("eyJ") || secret.length < 200) {
            Toast.makeText(requireContext(), "Anon Key غير صالح — تأكد من نسخه بالكامل (يبدأ بـ eyJ)", Toast.LENGTH_LONG).show()
            return
        }
        requireContext().getSharedPreferences(MainActivity.PREFS_NAME, 0).edit()
            .putString(MainActivity.KEY_WEBHOOK_URL, rawUrl)
            .putString(MainActivity.KEY_SECRET, secret)
            .apply()
        Toast.makeText(requireContext(), "✓ تم حفظ الإعدادات — يتم اختبار الاتصال الآن", Toast.LENGTH_SHORT).show()
        SmsMonitorService.start(requireContext())
        testConnection()
    }

    private fun forceRegister() {
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, 0)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)?.trim()
        val url = SupabaseConfig.getWebhookUrl(rawUrl)
        val secret = prefs.getString(MainActivity.KEY_SECRET, null)?.trim()
        if (url.isNullOrEmpty() || secret.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "احفظ الإعدادات أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (secret.isNullOrEmpty() || !secret.startsWith("eyJ") || secret.length < 200) {
            Toast.makeText(requireContext(), "Anon Key غير صالح — تأكد من نسخه بالكامل", Toast.LENGTH_LONG).show()
            return
        }
        binding.registerButton.isEnabled = false
        binding.testResultText.text = "جاري التسجيل…"
        val hb = HeartbeatManager(requireContext(), url, secret,
            onStatusChange = { connected, msg ->
                activity?.runOnUiThread {
                    binding.registerButton.isEnabled = true
                    if (connected) {
                        binding.testResultText.text = "✅ تم التسجيل بنجاح — $msg"
                        binding.testResultText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                        AppState.lastError.postValue(null)
                    } else {
                        binding.testResultText.text = "❌ فشل التسجيل — $msg"
                        binding.testResultText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    }
                }
            },
            onPendingTasks = { _ -> }
        )
        hb.registerDevice()
    }

    private fun testConnection() {
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, 0)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)?.trim()
        val url = SupabaseConfig.getWebhookUrl(rawUrl)
        val secret = prefs.getString(MainActivity.KEY_SECRET, null)?.trim()
        if (url.isNullOrEmpty() || secret.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "احفظ الإعدادات أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (secret.isNullOrEmpty() || !secret.startsWith("eyJ") || secret.length < 200) {
            Toast.makeText(requireContext(), "Anon Key غير صالح — تأكد من نسخه بالكامل", Toast.LENGTH_LONG).show()
            return
        }
        binding.testConnectionButton.isEnabled = false
        binding.testResultText.text = "جاري الاختبار…"
        val sentAt = System.currentTimeMillis()
        val body = mapOf(
            "action" to "test_ping",
            "device_id" to HeartbeatManager.getDeviceId(requireContext()),
            "device_model" to (Build.MODEL ?: "Unknown"),
            "android_version" to (Build.VERSION.RELEASE ?: "Unknown"),
            "app_version" to BuildConfig.VERSION_NAME,
            "sent_at" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(sentAt))
        )
        WebhookSender.sendJsonWithBody(url, secret, body) { success, msg, responseBody ->
            val elapsed = System.currentTimeMillis() - sentAt
            activity?.runOnUiThread {
                binding.testConnectionButton.isEnabled = true
                if (success) {
                    binding.testResultText.text = "✅ متصل — زمن الاستجابة: ${elapsed}ms"
                    binding.testResultText.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                    AppState.lastSyncTime.postValue(System.currentTimeMillis())
                    AppState.lastError.postValue(null)
                } else {
                    val errorMessage = when {
                        msg.contains("401") -> "❌ فشل: 401 — Anon Key غير صحيح. تأكد من النسخ الكامل."
                        msg.contains("404") -> "❌ فشل: 404 — رابط Supabase غير صحيح"
                        msg.contains("timeout", true) -> "❌ فشل: انتهت مهلة الاتصال — تأكد من الإنترنت"
                        else -> "❌ فشل: $msg"
                    }
                    binding.testResultText.text = errorMessage
                    binding.testResultText.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    AppState.lastError.postValue(errorMessage)
                }
            }
        }
    }

    private fun clearRetryQueue() {
        RetryQueue.clear(requireContext())
        binding.retryQueueSize.text = "الطابور: 0 عنصر"
        Toast.makeText(requireContext(), "تم مسح الطابور", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
