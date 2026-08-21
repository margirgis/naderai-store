package com.naderai.smsreader
import com.naderai.smsreader.BuildConfig

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

/**
 * Phase-3 Diagnostics — يعرض 15 حقل تشخيص + 6 أزرار + Order Timeline
 * لا تغيّر هذه الأزرار أي Business Data — تشخيص فقط.
 */
class DiagnosticsFragment : Fragment() {

    private lateinit var root: LinearLayout
    private val fmt = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())

    // ── 15 حقل تشخيص ──────────────────────────────────────────────────────────
    private lateinit var tvApiStatus: TextView
    private lateinit var tvAuthStatus: TextView
    private lateinit var tvDeviceReg: TextView
    private lateinit var tvHeartbeat: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var tvLastSyncResult: TextView
    private lateinit var tvRealtime: TextView
    private lateinit var tvPolling: TextView
    private lateinit var tvPendingQueue: TextView
    private lateinit var tvLastOrderRcv: TextView
    private lateinit var tvLastScan: TextView
    private lateinit var tvLastSmsMatch: TextView
    private lateinit var tvLastVerify: TextView
    private lateinit var tvLastError: TextView
    private lateinit var tvErrorCode: TextView
    private lateinit var tvTraceId: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvRetryQueue: TextView
    private lateinit var tvAppVersion: TextView

    // ── Timeline ──────────────────────────────────────────────────────────────
    private lateinit var timelineContainer: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(requireContext())
        scroll.addView(root)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildUI()
        observeState()
    }

    private fun buildUI() {
        // ── Section 1: الاتصال ────────────────────────────────────────────────
        addSectionTitle("📡 حالة الاتصال")
        tvApiStatus    = addRow("API متصل")
        tvAuthStatus   = addRow("مصادق (Auth)")
        tvDeviceReg    = addRow("الجهاز مسجَّل")
        tvHeartbeat    = addRow("Heartbeat")
        tvLastSync     = addRow("آخر Sync")
        tvLastSyncResult = addRow("نتيجة آخر Sync")
        tvRealtime     = addRow("Realtime متصل")
        tvPolling      = addRow("Polling نشط")

        addDivider()

        // ── Section 2: الطلبات ────────────────────────────────────────────────
        addSectionTitle("📋 حالة الطلبات")
        tvPendingQueue  = addRow("قائمة انتظار")
        tvLastOrderRcv  = addRow("آخر طلب وصل")
        tvLastScan      = addRow("آخر فحص")
        tvLastSmsMatch  = addRow("آخر تطابق SMS")
        tvLastVerify    = addRow("آخر تحقق")

        addDivider()

        // ── Section 3: أخطاء ──────────────────────────────────────────────────
        addSectionTitle("🚨 الأخطاء")
        tvLastError  = addRow("آخر خطأ")
        tvErrorCode  = addRow("كود الخطأ")
        tvTraceId    = addRow("Trace ID")

        addDivider()

        // ── Section 4: معلومات الجهاز ─────────────────────────────────────────
        addSectionTitle("📱 الجهاز")
        tvDeviceId   = addRow("Device ID")
        tvRetryQueue = addRow("طابور الإعادة")
        tvAppVersion = addRow("الإصدار")

        addDivider()

        // ── Section 5: أزرار التشخيص (لا تغيّر Business Data) ───────────────
        addSectionTitle("🔧 أدوات التشخيص")
        addButtonRow(
            listOf(
                "🔄 مزامنة الآن" to { SmsMonitorService.forceSync(requireContext()) },
                "🔌 اختبار الاتصال" to { testConnection() },
            )
        )
        addButtonRow(
            listOf(
                "📡 اختبار Realtime" to { testRealtime() },
                "📥 اختبار استلام طلب" to { testOrderReceive() },
            )
        )
        addButtonRow(
            listOf(
                "📤 إرسال Diagnostic Event" to { sendDiagEvent() },
                "📋 عرض آخر Events" to { showLastEvents() },
            )
        )

        addDivider()

        // ── Section 6: Order Timeline ─────────────────────────────────────────
        addSectionTitle("🕐 Timeline آخر طلب")
        timelineContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(timelineContainer)
    }

    // ── State observation ──────────────────────────────────────────────────────

    private fun observeState() {
        val ctx = requireContext()

        AppState.isConnected.observe(viewLifecycleOwner) { connected ->
            tvApiStatus.text = if (connected) "✅ متاح" else "❌ غير متاح"
            tvAuthStatus.text = if (connected) "✅ مصادق" else "❌ فشل"
            tvHeartbeat.text = if (connected) "✅ يعمل" else "❌ متوقف"
            tvRealtime.text = if (connected) "✅ متصل" else "⚠️ منفصل"
            tvPolling.text = if (SmsMonitorService.isRunning) "✅ نشط" else "❌ متوقف"
        }

        AppState.isRegistered.observe(viewLifecycleOwner) { reg ->
            tvDeviceReg.text = when (reg) {
                true  -> "✅ مسجَّل"
                false -> "❌ غير مسجَّل"
                null  -> "⏳ لم يُرسَل بعد"
            }
        }

        AppState.lastSyncTime.observe(viewLifecycleOwner) { ts ->
            tvLastSync.text = if (ts != null) fmt.format(Date(ts)) else "—"
        }

        AppState.pendingTasks.observe(viewLifecycleOwner) { tasks ->
            tvPendingQueue.text = "${tasks?.size ?: 0} طلب"
            tvPolling.text = if (SmsMonitorService.isRunning) "✅ نشط (${tasks?.size ?: 0})" else "❌ متوقف"
        }

        AppState.lastError.observe(viewLifecycleOwner) { err ->
            tvLastError.text = err?.take(80) ?: "—"
            tvLastSyncResult.text = if (err == null) "✅ ناجح" else "❌ فشل"
        }

        // مراقبة DiagnosticsLog للحقول الديناميكية
        OrderDiagnosticsLog.liveEntries.observe(viewLifecycleOwner) { entries ->
            // آخر طلب وصل
            entries.firstOrNull { it.type == OrderDiagnosticsLog.EventType.ORDER_RECEIVED }?.let {
                tvLastOrderRcv.text = "${fmt.format(Date(it.ts))} #${it.orderNumber ?: "?"}"
            }
            // آخر فحص
            entries.firstOrNull { it.type == OrderDiagnosticsLog.EventType.SCAN_STARTED }?.let {
                tvLastScan.text = "${fmt.format(Date(it.ts))} #${it.orderNumber ?: "?"}"
            }
            // آخر تطابق
            entries.firstOrNull { it.type == OrderDiagnosticsLog.EventType.SMS_MATCH_FOUND }?.let {
                tvLastSmsMatch.text = "${fmt.format(Date(it.ts))} #${it.orderNumber ?: "?"}"
            }
            // آخر تحقق
            entries.firstOrNull { it.type in setOf(OrderDiagnosticsLog.EventType.VERIFY_RESULT, OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK) }?.let {
                tvLastVerify.text = "${fmt.format(Date(it.ts))} ${it.type.emoji}"
            }
            // آخر خطأ مع كود وtrace
            val errEntry = OrderDiagnosticsLog.getLastError()
            if (errEntry != null) {
                tvLastError.text = "${errEntry.type.label}: ${errEntry.details?.take(60) ?: "—"}"
                tvErrorCode.text = errEntry.serverCode?.toString() ?: errEntry.type.name
                tvTraceId.text = errEntry.traceId ?: "—"
            }
            // Timeline آخر طلب
            refreshTimeline(entries)
        }

        // Static info
        val deviceId = HeartbeatManager.getDeviceId(ctx)
        tvDeviceId.text = deviceId.take(8) + "****" + deviceId.takeLast(4)
        tvRetryQueue.text = "${RetryQueue.size(ctx)} عنصر"
        tvAppVersion.text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — Android ${Build.VERSION.RELEASE}"
    }

    // ── Timeline ──────────────────────────────────────────────────────────────

    private fun refreshTimeline(entries: List<OrderDiagnosticsLog.LogEntry>) {
        timelineContainer.removeAllViews()
        // خذ أحدث طلب له traceId
        val latestTrace = entries.firstOrNull { !it.traceId.isNullOrEmpty() }?.traceId ?: return
        val relevant = entries.filter { it.traceId == latestTrace }.reversed() // أقدم أولاً
        if (relevant.isEmpty()) return

        relevant.forEach { entry ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            val dot = TextView(requireContext()).apply {
                text = entry.type.emoji
                textSize = 14f
                width = 60
                gravity = Gravity.CENTER
            }
            val info = TextView(requireContext()).apply {
                text = buildString {
                    append("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.ts))}] ")
                    append(entry.type.label)
                    if (!entry.details.isNullOrEmpty()) append(" — ${entry.details.take(50)}")
                    if (entry.durationMs != null) append(" (${entry.durationMs}ms)")
                }
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                // تلوين حالات الفشل
                if (entry.type in setOf(
                        OrderDiagnosticsLog.EventType.SMS_SCAN_FAILED,
                        OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL,
                        OrderDiagnosticsLog.EventType.NETWORK_ERROR,
                        OrderDiagnosticsLog.EventType.AUTH_ERROR,
                    )) {
                    setTextColor(0xFFDC2626.toInt())
                }
            }
            row.addView(dot)
            row.addView(info)
            timelineContainer.addView(row)
        }

        // عرض trace_id قابل للنسخ
        val traceRow = TextView(requireContext()).apply {
            text = "Trace: $latestTrace"
            textSize = 10f
            setTextColor(0xFF6B7280.toInt())
            setPadding(60, 4, 0, 8)
            setOnLongClickListener {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("trace_id", latestTrace))
                Toast.makeText(requireContext(), "Trace ID نُسخ", Toast.LENGTH_SHORT).show()
                true
            }
        }
        timelineContainer.addView(traceRow)
    }

    // ── أزرار التشخيص — لا تغيّر Business Data ───────────────────────────────

    private fun testConnection() {
        Toast.makeText(requireContext(), "⏳ اختبار الاتصال...", Toast.LENGTH_SHORT).show()
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
        val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
        if (webhookUrl.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "❌ لا يوجد Webhook URL مُعيَّن", Toast.LENGTH_LONG).show()
            return
        }
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        if (secret.isEmpty()) {
            Toast.makeText(requireContext(), "❌ لا يوجد Secret مُعيَّن في الإعدادات", Toast.LENGTH_LONG).show()
            return
        }
        // يجب إرسال action=test_ping وليس ping — الـ server يتحقق من القيمة الدقيقة
        val payload = mapOf(
            "action" to "test_ping",
            "device_id" to HeartbeatManager.getDeviceId(requireContext())
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { success, msg, responseBody ->
            activity?.runOnUiThread {
                val displayMsg = when {
                    success -> "✅ API متصل ويعمل"
                    responseBody.contains("401") || msg.contains("401") ->
                        "❌ خطأ مصادقة (401) — تأكد من صحة الـ Secret في الإعدادات"
                    responseBody.contains("Invalid secret") ->
                        "❌ Secret غير صحيح — راجع إعدادات الـ Webhook Secret"
                    else -> "❌ فشل: $msg"
                }
                Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_LONG).show()
                tvApiStatus.text = if (success) "✅ متاح" else "❌ $msg"
            }
        }
    }

    private fun testRealtime() {
        // تشخيص: هل Realtime متصل من خلال حالة Service
        val running = SmsMonitorService.isRunning
        Toast.makeText(requireContext(),
            if (running) "✅ Realtime/Polling نشط" else "❌ Service متوقف",
            Toast.LENGTH_SHORT).show()
        tvRealtime.text = if (running) "✅ متصل" else "❌ منفصل"
    }

    private fun testOrderReceive() {
        // تشخيص: يعرض آخر طلب وصل من الـ logs
        val last = OrderDiagnosticsLog.getLastOfType(OrderDiagnosticsLog.EventType.ORDER_RECEIVED)
        if (last == null) {
            Toast.makeText(requireContext(), "ℹ️ لم يصل أي طلب بعد", Toast.LENGTH_SHORT).show()
        } else {
            val msg = "آخر طلب: #${last.orderNumber ?: last.requestId?.take(8)} — ${fmt.format(Date(last.ts))}"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendDiagEvent() {
        // يُرسل حدث تشخيصي بحت — لا يؤثر على أي Order
        OrderDiagnosticsLog.log(
            OrderDiagnosticsLog.EventType.GENERIC_ERROR,
            details = "DIAG_TEST from ${HeartbeatManager.getDeviceId(requireContext()).take(8)}"
        )
        Toast.makeText(requireContext(), "✅ تم إرسال Diagnostic Event في السجل", Toast.LENGTH_SHORT).show()
    }

    private fun showLastEvents() {
        val recent = OrderDiagnosticsLog.getRecent(20)
        if (recent.isEmpty()) {
            Toast.makeText(requireContext(), "لا توجد أحداث", Toast.LENGTH_SHORT).show()
            return
        }
        // عرض Dialog مع آخر 20 حدث
        val sb = StringBuilder()
        recent.take(20).forEach { e ->
            sb.appendLine("${e.type.emoji} ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(e.ts))} ${e.type.label}")
            if (!e.details.isNullOrEmpty()) sb.appendLine("   └ ${e.details.take(60)}")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("آخر 20 Event")
            .setMessage(sb.toString())
            .setPositiveButton("موافق", null)
            .setNeutralButton("نسخ") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("events", OrderDiagnosticsLog.exportText()))
                Toast.makeText(requireContext(), "نُسخ كامل السجل", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun addSectionTitle(text: String) {
        root.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        })
    }

    private fun addRow(label: String): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5, 0, 5)
        }
        val lbl = TextView(requireContext()).apply {
            text = "$label:"
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(requireContext()).apply {
            text = "—"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        row.addView(lbl)
        row.addView(value)
        root.addView(row)
        return value
    }

    private fun addButtonRow(buttons: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        buttons.forEach { (label, action) ->
            val btn = Button(requireContext()).apply {
                text = label
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.setMargins(0, 0, 8, 0) }
                setOnClickListener { action() }
            }
            row.addView(btn)
        }
        root.addView(row)
    }

    private fun addDivider() {
        root.addView(View(requireContext()).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 12, 0, 4) }
        })
    }
}
