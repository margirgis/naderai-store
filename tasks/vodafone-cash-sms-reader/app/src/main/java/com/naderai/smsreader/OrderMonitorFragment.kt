package com.naderai.smsreader

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * شاشة المراقبة الشاملة — تعرض كل حدث مرّ بأي طلب:
 *   📥 وصل ← 🔍 فُحص ← ✅/❌ نتيجة ← 📤 أُرسل ← 🟢/🔴 رد السيرفر
 *
 * مميزات:
 *  • تحديث فوري (LiveData)
 *  • فلترة بالنوع أو رقم الطلب
 *  • تصدير JSON أو TXT (مشاركة/حفظ)
 *  • مسح السجل
 */
class OrderMonitorFragment : Fragment() {

    private lateinit var root: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DiagAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvSummary: TextView
    private lateinit var etFilter: EditText
    private lateinit var spinnerType: Spinner
    private var filterText = ""
    private var filterType: OrderDiagnosticsLog.EventType? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildUI()
        observeLiveData()
    }

    private fun buildUI() {
        val ctx = requireContext()
        val padding = dp(12)

        // ── رأس الصفحة ───────────────────────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, dp(16), padding, dp(8))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(ctx).apply {
            text = "🔬 مراقبة الطلبات"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvCount = TextView(ctx).apply {
            text = "0 حدث"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        header.addView(tvTitle)
        header.addView(tvCount)
        root.addView(header)

        // ── ملخص سريع ────────────────────────────────────────────────────
        tvSummary = TextView(ctx).apply {
            setPadding(padding, 0, padding, dp(8))
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        root.addView(tvSummary)

        // ── فلتر نصي ─────────────────────────────────────────────────────
        etFilter = EditText(ctx).apply {
            hint = "🔍 بحث: رقم طلب، نوع، تفاصيل..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#475569"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(padding, dp(10), padding, dp(10))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(padding, 0, padding, dp(6))
            layoutParams = params
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    filterText = s?.toString().orEmpty().trim()
                    applyFilter()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        root.addView(etFilter)

        // ── فلتر النوع ────────────────────────────────────────────────────
        val typeLabels = mutableListOf("كل الأحداث") +
            OrderDiagnosticsLog.EventType.values().map { "${it.emoji} ${it.label}" }
        spinnerType = Spinner(ctx).apply {
            val spinParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            spinParams.setMargins(padding, 0, padding, dp(8))
            layoutParams = spinParams
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, typeLabels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    filterType = if (pos == 0) null
                    else OrderDiagnosticsLog.EventType.values()[pos - 1]
                    applyFilter()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(spinnerType)

        // ── أزرار الأدوات ────────────────────────────────────────────────
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, 0, padding, dp(8))
        }
        fun makeBtn(label: String, color: String, onClick: () -> Unit): Button =
            Button(ctx).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color))
                val bp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                bp.setMargins(0, 0, dp(4), 0)
                layoutParams = bp
                setOnClickListener { onClick() }
            }
        btnRow.addView(makeBtn("📋 JSON", "#1D4ED8") { exportFile("json") })
        btnRow.addView(makeBtn("📄 TXT", "#0F766E") { exportFile("txt") })
        btnRow.addView(makeBtn("🗑 مسح", "#B91C1C") { clearLog() })
        root.addView(btnRow)

        // ── قائمة الأحداث ─────────────────────────────────────────────────
        adapter = DiagAdapter()
        recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = this@OrderMonitorFragment.adapter
            setBackgroundColor(Color.parseColor("#0F172A"))
            val rp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutParams = rp
        }
        root.addView(recycler)
    }

    private fun observeLiveData() {
        OrderDiagnosticsLog.liveEntries.observe(viewLifecycleOwner) { entries ->
            updateSummary(entries)
            applyFilter(entries)
        }
    }

    private fun applyFilter(
        source: List<OrderDiagnosticsLog.LogEntry>? = null
    ) {
        val base = source ?: OrderDiagnosticsLog.getAll()
        val filtered = base.filter { entry ->
            val matchText = filterText.isEmpty() ||
                (entry.orderNumber?.toString()?.contains(filterText) == true) ||
                entry.requestId?.contains(filterText, true) == true ||
                entry.type.label.contains(filterText, true) ||
                entry.type.name.contains(filterText, true) ||
                entry.details?.contains(filterText, true) == true ||
                entry.serverResponse?.contains(filterText, true) == true
            val matchType = filterType == null || entry.type == filterType
            matchText && matchType
        }
        tvCount.text = "${filtered.size} حدث"
        adapter.submitList(filtered)
        if (filtered.isNotEmpty()) recycler.scrollToPosition(0)
    }

    private fun updateSummary(entries: List<OrderDiagnosticsLog.LogEntry>) {
        val received  = entries.count { it.type == OrderDiagnosticsLog.EventType.ORDER_RECEIVED }
        val scanned   = entries.count { it.type == OrderDiagnosticsLog.EventType.SCAN_STARTED }
        val matched   = entries.count { it.type == OrderDiagnosticsLog.EventType.SMS_MATCH_FOUND }
        val notFound  = entries.count { it.type == OrderDiagnosticsLog.EventType.SMS_NOT_FOUND }
        val skipped   = entries.count { it.type == OrderDiagnosticsLog.EventType.ORDER_SKIPPED ||
                                        it.type == OrderDiagnosticsLog.EventType.TERMINAL_IGNORED }
        val errors    = entries.count { it.type in setOf(
            OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL,
            OrderDiagnosticsLog.EventType.SERVER_RESPONSE_ERROR,
            OrderDiagnosticsLog.EventType.AUTH_ERROR,
            OrderDiagnosticsLog.EventType.NETWORK_ERROR,
            OrderDiagnosticsLog.EventType.CONSTRAINT_ERROR,
        )}
        tvSummary.text =
            "📥 وصل=$received  🔍 فُحص=$scanned  ✅ طابق=$matched  " +
            "❌ لم يوجد=$notFound  ⏭ تجاهل=$skipped  🚨 أخطاء=$errors"
    }

    private fun exportFile(format: String) {
        val ctx = requireContext()
        val file = OrderDiagnosticsLog.saveToFile(ctx, format)
        if (file == null) {
            Toast.makeText(ctx, "فشل التصدير", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (format == "json") "application/json" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "سجل تشخيصات — ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "مشاركة السجل"))
        } catch (e: Exception) {
            // FileProvider قد لا يكون مُعد — نعطي رسالة بالمسار
            Toast.makeText(ctx,
                "تم الحفظ:\n${file.absolutePath}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun clearLog() {
        OrderDiagnosticsLog.clear()
        Toast.makeText(requireContext(), "تم مسح السجل", Toast.LENGTH_SHORT).show()
    }

    private fun dp(n: Int): Int =
        (n * requireContext().resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
    }

    // ── Adapter داخلي ─────────────────────────────────────────────────────

    inner class DiagAdapter : RecyclerView.Adapter<DiagAdapter.VH>() {

        private var list: List<OrderDiagnosticsLog.LogEntry> = emptyList()

        fun submitList(newList: List<OrderDiagnosticsLog.LogEntry>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val p = (8 * ctx.resources.displayMetrics.density).toInt()
                setPadding(p * 2, p, p * 2, p)
                val mp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(p, p / 2, p, p / 2) }
                layoutParams = mp
            }
            val tvHeader = TextView(ctx).apply { textSize = 12.5f; setTextColor(Color.WHITE) }
            val tvDetail = TextView(ctx).apply { textSize = 11f; setTextColor(Color.parseColor("#94A3B8")) }
            val tvServer = TextView(ctx).apply { textSize = 10f; setTextColor(Color.parseColor("#64748B")); visibility = View.GONE }
            card.addView(tvHeader)
            card.addView(tvDetail)
            card.addView(tvServer)
            return VH(card, tvHeader, tvDetail, tvServer)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = list[position]
            val isError = e.type in setOf(
                OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL,
                OrderDiagnosticsLog.EventType.SERVER_RESPONSE_ERROR,
                OrderDiagnosticsLog.EventType.AUTH_ERROR,
                OrderDiagnosticsLog.EventType.NETWORK_ERROR,
                OrderDiagnosticsLog.EventType.CONSTRAINT_ERROR,
                OrderDiagnosticsLog.EventType.GENERIC_ERROR,
                OrderDiagnosticsLog.EventType.SMS_NOT_FOUND,
                OrderDiagnosticsLog.EventType.SMS_AMOUNT_MISMATCH,
                OrderDiagnosticsLog.EventType.TERMINAL_IGNORED,
                OrderDiagnosticsLog.EventType.ORDER_SKIPPED,
            )
            val isSuccess = e.type in setOf(
                OrderDiagnosticsLog.EventType.SMS_MATCH_FOUND,
                OrderDiagnosticsLog.EventType.SERVER_RESPONSE_OK,
                OrderDiagnosticsLog.EventType.MANUAL_CONFIRM_OK,
            )
            val bgColor = when {
                isError   -> Color.parseColor("#1A1A2E")
                isSuccess -> Color.parseColor("#0A1A14")
                else      -> Color.parseColor("#1E293B")
            }
            holder.card.setBackgroundColor(bgColor)

            val orderTag = if (e.orderNumber != null) " طلب#${e.orderNumber}" else ""
            holder.tvHeader.text = "${e.type.emoji} ${e.type.label}$orderTag  ·  ${e.tsFormatted}"
            val headerColor = when {
                isError   -> Color.parseColor("#F87171")
                isSuccess -> Color.parseColor("#4ADE80")
                else      -> Color.WHITE
            }
            holder.tvHeader.setTextColor(headerColor)

            val detailParts = mutableListOf<String>()
            if (!e.requestId.isNullOrEmpty()) detailParts.add("req=${e.requestId.take(12)}")
            if (!e.taskId.isNullOrEmpty()) detailParts.add("task=${e.taskId.take(12)}")
            if (!e.details.isNullOrEmpty()) detailParts.add(e.details)
            holder.tvDetail.text = detailParts.joinToString("  ·  ")
            holder.tvDetail.visibility = if (detailParts.isEmpty()) View.GONE else View.VISIBLE

            if (!e.serverResponse.isNullOrEmpty() || e.serverCode != null) {
                val code = if (e.serverCode != null) "HTTP ${e.serverCode}: " else ""
                holder.tvServer.text = "$code${e.serverResponse.orEmpty().take(200)}"
                holder.tvServer.visibility = View.VISIBLE
            } else {
                holder.tvServer.visibility = View.GONE
            }
        }

        override fun getItemCount() = list.size

        inner class VH(
            val card: LinearLayout,
            val tvHeader: TextView,
            val tvDetail: TextView,
            val tvServer: TextView
        ) : RecyclerView.ViewHolder(card)
    }
}
