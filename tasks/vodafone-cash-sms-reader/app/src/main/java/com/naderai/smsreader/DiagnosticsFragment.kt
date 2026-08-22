package com.naderai.smsreader
import com.naderai.smsreader.BuildConfig

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Fix #4: debounce observer لـ liveEntries لتجنّب تحديثات UI كثيرة جداً
 */
class DiagnosticsFragment : Fragment() {

    private lateinit var root: LinearLayout
    private val fmt = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())

    // Fix #4: debounce handler لـ liveEntries
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingEntriesUpdate: List<OrderDiagnosticsLog.LogEntry>? = null
    private val DEBOUNCE_MS = 400L
    private val entriesDebounceRunnable = Runnable {
        val entries = pendingEntriesUpdate ?: return@Runnable
        pendingEntriesUpdate = null
        if (!isAdded) return@Runnable
        applyEntriesUpdate(entries)
    }

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

    override fun onDestroyView() {
        // Fix #4: تنظيف الـ debounce handler لمنع memory leak
        uiHandler.removeCallbacks(entriesDebounceRunnable)
        pendingEntriesUpdate = null
        super.onDestroyView()
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

        // ── Fix #7: اتصال وتسجيل ─────────────────────────────────────────────
        AppState.isConnected.observe(viewLifecycleOwner) { connected ->
            tvApiStatus.text    = if (connected) "✅ متاح" else "❌ غير متاح"
            tvAuthStatus.text   = if (connected) "✅ مصادق" else "❌ فشل"
            tvHeartbeat.text    = if (connected) "✅ يعمل" else "❌ متوقف"
            tvRealtime.text     = if (connected) "✅ متصل" else "⚠️ منفصل"
        }

        AppState.isRegistered.observe(viewLifecycleOwner) { reg ->
            tvDeviceReg.text = when (reg) {
                true  -> "✅ مسجَّل"
                false -> "❌ غير مسجَّل"
                null  -> "⏳ لم يُرسَل بعد"
            }
        }

        // ── Fix #7: آخر sync ─────────────────────────────────────────────────
        AppState.lastSyncTime.observe(viewLifecycleOwner) { ts ->
            tvLastSync.text = if (ts != null) fmt.format(Date(ts)) else "—"
            tvLastSyncResult.text = if (ts != null) "✅ ناجح" else "—"
        }

        // ── Fix #7: قائمة الانتظار الحية = pendingTasks + service status ─────
        AppState.pendingTasks.observe(viewLifecycleOwner) { tasks ->
            val localQueueSize = LocalSmsQueue.size(requireContext())
            val pendingCount = tasks?.size ?: 0
            tvPendingQueue.text = buildString {
                append("$pendingCount مهمة")
                if (localQueueSize > 0) append(" + $localQueueSize SMS محلي")
            }
            tvPolling.text = if (SmsMonitorService.isRunning) "✅ نشط ($pendingCount)" else "❌ متوقف"
        }

        // ── Fix #7: عدد الطلبات الحي مع تفصيل الحالات ──────────────────────
        AppState.orders.observe(viewLifecycleOwner) { orders ->
            val total     = orders?.size ?: 0
            val pending   = orders?.count { it.status in setOf(
                OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.SCANNING,
                OrderStatus.SMS_FOUND, OrderStatus.REVIEWING,
                OrderStatus.WAITING_CONFIRMATION) } ?: 0
            val confirmed = orders?.count { it.status in setOf(
                OrderStatus.COMPLETED, OrderStatus.CONFIRMED) } ?: 0
            val failed    = orders?.count { it.status in setOf(
                OrderStatus.FAILED, OrderStatus.NOT_FOUND,
                OrderStatus.AMOUNT_MISMATCH, OrderStatus.EXPIRED) } ?: 0
            // نعرض الملخص في حقل آخر طلب وصل
            tvLastOrderRcv.text = "الكل=$total ⏳=$pending ✅=$confirmed ❌=$failed"
        }

        // ── Fix #7: آخر خطأ ──────────────────────────────────────────────────
        AppState.lastError.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                tvLastError.text    = err.take(80)
                tvLastSyncResult.text = "❌ فشل"
            }
        }

        // ── Fix #4: DiagnosticsLog — debounce 400ms لتجنّب تحديث UI كل sync ────
        OrderDiagnosticsLog.liveEntries.observe(viewLifecycleOwner) { entries ->
            pendingEntriesUpdate = entries
            uiHandler.removeCallbacks(entriesDebounceRunnable)
            uiHandler.postDelayed(entriesDebounceRunnable, DEBOUNCE_MS)
        }

        // ── معلومات ثابتة (تُحدَّث مرة واحدة) ──────────────────────────────
        val deviceId = HeartbeatManager.getDeviceId(ctx)
        tvDeviceId.text   = deviceId.take(8) + "****" + deviceId.takeLast(4)
        tvRetryQueue.text = "${RetryQueue.size(ctx)} عنصر"
        tvAppVersion.text =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — Android ${Build.VERSION.RELEASE}"
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
        val payload = mapOf(
            "action" to "test_ping",
            "device_id" to HeartbeatManager.getDeviceId(requireContext())
        )
        WebhookSender.sendJsonWithBody(webhookUrl, secret, payload) { businessOk, msg, responseBody ->
            activity?.runOnUiThread {
                // Fix #2: عرض الفرق الواضح — HTTP 200 + ok=false = API_FAILED وليس API_CONNECTED
                val httpCode = Regex("\\b([1-5]\\d{2})\\b").find(msg)?.value?.toIntOrNull()
                val displayMsg = when {
                    businessOk ->
                        "✅ API متصل ويعمل (HTTP 200 + ok=true)"
                    msg.contains("API_FAILED") ->
                        "⚠️ HTTP 200 لكن ok=false — API_FAILED\n${responseBody.take(100)}"
                    httpCode == 401 || msg.contains("401") ->
                        "❌ خطأ مصادقة (401) — تأكد من Secret"
                    httpCode == 403 ->
                        "❌ غير مصرح (403) — Secret خاطئ"
                    msg.contains("Invalid secret") ->
                        "❌ Secret غير صحيح"
                    else ->
                        "❌ فشل: $msg"
                }
                Toast.makeText(requireContext(), displayMsg, Toast.LENGTH_LONG).show()
                // Fix #2: حالة API تعكس business success لا HTTP فقط
                tvApiStatus.text = if (businessOk) "✅ متاح (ok=true)" else "❌ API_FAILED — $msg"
                // تسجيل في DiagnosticsLog
                OrderDiagnosticsLog.log(
                    if (businessOk) OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK
                    else OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL,
                    details = "[TEST_CONNECTION] businessOk=$businessOk http=${httpCode ?: "?"} msg=${msg.take(60)}"
                )
            }
        }
    }

    private fun testRealtime() {
        // Fix #10: يفحص حالة Service + Heartbeat الفعلية لا يصنع حدثاً وهمياً
        val running = SmsMonitorService.isRunning
        val pendingCount = AppState.pendingTasks.value?.size ?: 0
        val retryCount = RetryQueue.size(requireContext())
        val msg = buildString {
            if (running) append("✅ Service نشط") else append("❌ Service متوقف")
            append(" | انتظار=$pendingCount | إعادة=$retryCount")
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        tvRealtime.text = if (running) "✅ متصل ($pendingCount طلب)" else "❌ منفصل"
        tvRetryQueue.text = "$retryCount عنصر"
    }

    private fun testOrderReceive() {
        // Fix #10: يعرض بيانات حقيقية من DiagnosticsLog بدون إنشاء أحداث اصطناعية
        val lastReceived = OrderDiagnosticsLog.getLastOfType(OrderDiagnosticsLog.EventType.ORDER_RECEIVED)
        val lastSkipped  = OrderDiagnosticsLog.getLastOfType(OrderDiagnosticsLog.EventType.ORDER_SKIPPED)
        val sb = StringBuilder()
        if (lastReceived != null) {
            sb.appendLine("📥 آخر طلب وصل:")
            sb.appendLine("  رقم: #${lastReceived.orderNumber ?: lastReceived.requestId?.take(8)}")
            sb.appendLine("  وقت: ${fmt.format(Date(lastReceived.ts))}")
            if (!lastReceived.details.isNullOrEmpty()) sb.appendLine("  تفاصيل: ${lastReceived.details.take(80)}")
        } else {
            sb.appendLine("ℹ️ لم يصل أي طلب بعد")
        }
        if (lastSkipped != null) {
            sb.appendLine()
            sb.appendLine("⏭ آخر طلب مُجاهَل:")
            sb.appendLine("  رقم: #${lastSkipped.orderNumber ?: lastSkipped.requestId?.take(8)}")
            sb.appendLine("  سبب: ${lastSkipped.details?.take(80) ?: "غير معروف"}")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("📥 استلام الطلبات")
            .setMessage(sb.toString())
            .setPositiveButton("موافق", null)
            .show()
    }

    private fun sendDiagEvent() {
        // Fix #10: حدث تشخيصي مُصنَّف بوضوح TEST — لا يختلط مع أحداث الطلبات
        val deviceId = HeartbeatManager.getDeviceId(requireContext()).take(8)
        OrderDiagnosticsLog.log(
            OrderDiagnosticsLog.EventType.GENERIC_ERROR,
            details = "[TEST] DIAG_TEST from $deviceId — not a real order event"
        )
        Toast.makeText(requireContext(), "✅ تم تسجيل Diagnostic Event (مُصنَّف TEST)", Toast.LENGTH_SHORT).show()
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
