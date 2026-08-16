package com.naderai.smsreader
import com.naderai.smsreader.BuildConfig

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

/**
 * Diagnostics screen — shows the full connection checklist:
 * API ✓/✗  |  Registration ✓/✗  |  Heartbeat ✓/✗  |  Orders Sync ✓/✗
 * Last server response, last error, device ID (masked), retry queue size
 */
class DiagnosticsFragment : Fragment() {

    private lateinit var root: LinearLayout
    private val fmt = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())

    private lateinit var tvApiStatus: TextView
    private lateinit var tvAuthStatus: TextView
    private lateinit var tvRegStatus: TextView
    private lateinit var tvHeartbeatStatus: TextView
    private lateinit var tvOrdersStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvLastError: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvRetryQueue: TextView
    private lateinit var tvAppVersion: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = android.widget.ScrollView(requireContext())
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildUI()
        observeState()
    }

    private fun buildUI() {
        addTitle("🔍 تشخيص الاتصال")
        tvApiStatus      = addRow("الـ API")
        tvAuthStatus     = addRow("المصادقة")
        tvRegStatus      = addRow("تسجيل الجهاز")
        tvHeartbeatStatus = addRow("Heartbeat")
        tvOrdersStatus   = addRow("مزامنة الطلبات")
        addDivider()
        tvLastSync       = addRow("آخر استجابة")
        tvLastError      = addRow("آخر خطأ")
        tvDeviceId       = addRow("Device ID")
        tvRetryQueue     = addRow("طابور الإعادة")
        tvAppVersion     = addRow("إصدار التطبيق")
    }

    private fun observeState() {
        val ctx = requireContext()

        // Determine API reachability from connection state
        AppState.isConnected.observe(viewLifecycleOwner) { connected ->
            tvApiStatus.text = if (connected) "✅ متاح" else "❌ غير متاح"
            tvAuthStatus.text = if (connected) "✅ مصادق" else "❌ فشل / غير معروف"
            tvHeartbeatStatus.text = if (connected) "✅ يعمل" else "❌ متوقف"
        }
        AppState.isRegistered.observe(viewLifecycleOwner) { registered ->
            tvRegStatus.text = when (registered) {
                true  -> "✅ مسجل"
                false -> "❌ غير مسجل"
                null  -> "⏳ لم يُرسل بعد"
            }
        }
        AppState.orders.observe(viewLifecycleOwner) { orders ->
            tvOrdersStatus.text = if (orders.isNotEmpty()) "✅ ${orders.size} طلب" else "⏳ لا يوجد طلب"
        }
        AppState.lastSyncTime.observe(viewLifecycleOwner) { ts ->
            tvLastSync.text = if (ts != null) fmt.format(Date(ts)) else "—"
        }
        AppState.lastError.observe(viewLifecycleOwner) { err ->
            tvLastError.text = err ?: "—"
        }

        // Static info
        val deviceId = HeartbeatManager.getDeviceId(ctx)
        tvDeviceId.text = deviceId.take(8) + "****" + deviceId.takeLast(4)
        tvRetryQueue.text = "${RetryQueue.size(ctx)} عنصر"
        tvAppVersion.text = "${BuildConfig.VERSION_NAME} — Android ${Build.VERSION.RELEASE} — ${Build.MODEL}"
    }

    private fun addTitle(text: String) {
        root.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })
    }

    private fun addRow(label: String): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        val lbl = TextView(requireContext()).apply {
            text = "$label:"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(requireContext()).apply {
            text = "—"
            textSize = 13f
        }
        row.addView(lbl)
        row.addView(value)
        root.addView(row)
        return value
    }

    private fun addDivider() {
        root.addView(View(requireContext()).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 12, 0, 12) }
        })
    }
}
