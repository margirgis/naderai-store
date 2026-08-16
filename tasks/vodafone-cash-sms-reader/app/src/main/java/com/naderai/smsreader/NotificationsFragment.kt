package com.naderai.smsreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class NotificationsFragment : Fragment() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var markAllReadBtn: Button
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Header row
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

        listView = ListView(requireContext())
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppState.notifications.observe(viewLifecycleOwner) { list ->
            if (list.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                listView.adapter = null
            } else {
                emptyText.visibility = View.GONE
                listView.adapter = NotificationAdapter(list)
            }
        }
    }

    inner class NotificationAdapter(private val items: List<DeviceNotification>) :
        ArrayAdapter<DeviceNotification>(requireContext(), 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val n = items[position]
            val ctx = requireContext()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(if (!n.isRead) 0x0F3B82F6 else 0x00000000)
            }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val icon = TextView(ctx).apply {
                text = when (n.type) {
                    NotificationType.CONNECTED -> "🟢"
                    NotificationType.ERROR -> "🔴"
                    NotificationType.ORDER_NEW -> "📋"
                    NotificationType.ORDER_CONFIRMED -> "✅"
                    NotificationType.ORDER_NOT_FOUND -> "❓"
                    NotificationType.ORDER_MISMATCH -> "⚠️"
                    NotificationType.TEST_SUCCESS -> "🧪"
                    NotificationType.TEST_RECEIVED -> "📡"
                    NotificationType.SERVER_DOWN -> "🔴"
                }
                textSize = 14f
                setPadding(0, 0, 8, 0)
            }
            val titleTv = TextView(ctx).apply {
                text = n.title
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeTv = TextView(ctx).apply {
                text = fmt.format(Date(n.timestamp))
                textSize = 11f
                setTextColor(0xFF9CA3AF.toInt())
            }
            titleRow.addView(icon)
            titleRow.addView(titleTv)
            titleRow.addView(timeTv)
            row.addView(titleRow)

            val msgTv = TextView(ctx).apply {
                text = n.message
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
                setPadding(22, 2, 0, 0)
            }
            row.addView(msgTv)
            return row
        }
    }
}
