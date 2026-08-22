package com.naderai.smsreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var markAllReadBtn: Button
    // Fix #3: RecyclerView + ListAdapter + ViewHolder بدلاً من ListView + ArrayAdapter بدون ViewHolder
    private val adapter = NotificationAdapter()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(requireContext()).apply {
            text = "🔔 الإشعارات"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        markAllReadBtn = Button(requireContext()).apply {
            text = "قراءة الكل"
            textSize = 12f
            setOnClickListener { AppState.markAllNotificationsRead() }
        }
        header.addView(title)
        header.addView(markAllReadBtn)
        root.addView(header)

        emptyText = TextView(requireContext()).apply {
            text = "لا توجد إشعارات"
            gravity = android.view.Gravity.CENTER
            setPadding(0, 64, 0, 0)
            visibility = View.GONE
        }
        root.addView(emptyText)

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@NotificationsFragment.adapter
            setHasFixedSize(false)
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppState.notifications.observe(viewLifecycleOwner) { list ->
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(list)
        }
    }

    // Fix #3: ViewHolder pattern — لا View inflation في كل getView
    // NotificationAdapter ليست inner class لأن companion object لا يعمل داخل inner class في Kotlin
    class NotificationAdapter : ListAdapter<DeviceNotification, NotificationAdapter.VH>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<DeviceNotification>() {
                override fun areItemsTheSame(a: DeviceNotification, b: DeviceNotification) = a.id == b.id
                override fun areContentsTheSame(a: DeviceNotification, b: DeviceNotification) = a == b
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val icon    = TextView(ctx).apply { textSize = 14f; setPadding(0, 0, 8, 0) }
            val titleTv = TextView(ctx).apply {
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeTv = TextView(ctx).apply { textSize = 11f; setTextColor(0xFF9CA3AF.toInt()) }
            titleRow.addView(icon); titleRow.addView(titleTv); titleRow.addView(timeTv)
            row.addView(titleRow)
            val msgTv = TextView(ctx).apply {
                textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(22, 2, 0, 0)
            }
            row.addView(msgTv)
            return VH(row, icon, titleTv, timeTv, msgTv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = getItem(position)
            holder.icon.text = when (n.type) {
                NotificationType.CONNECTED       -> "🟢"
                NotificationType.ERROR           -> "🔴"
                NotificationType.INFO            -> "ℹ️"
                NotificationType.ORDER_NEW       -> "📋"
                NotificationType.ORDER_CONFIRMED -> "✅"
                NotificationType.ORDER_NOT_FOUND -> "❓"
                NotificationType.ORDER_MISMATCH  -> "⚠️"
                NotificationType.TEST_SUCCESS    -> "🧪"
                NotificationType.TEST_RECEIVED   -> "📡"
                NotificationType.SERVER_DOWN     -> "🔴"
            }
            holder.titleTv.text = n.title
            holder.timeTv.text  = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(n.timestamp))
            holder.msgTv.text   = n.message
            holder.row.setBackgroundColor(if (!n.isRead) 0x0F3B82F6 else 0x00000000)
        }

        class VH(
            val row: LinearLayout,
            val icon: TextView,
            val titleTv: TextView,
            val timeTv: TextView,
            val msgTv: TextView
        ) : RecyclerView.ViewHolder(row)
    }
}
