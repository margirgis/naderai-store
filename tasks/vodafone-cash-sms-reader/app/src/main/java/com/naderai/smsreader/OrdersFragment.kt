package com.naderai.smsreader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.naderai.smsreader.databinding.FragmentOrdersBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrdersFragment : Fragment() {

    private var _binding: FragmentOrdersBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: OrderAdapter
    private var filterStatus: OrderStatus? = null

    companion object {
        private const val ARG_STATUS = "status"
        fun newInstance(status: OrderStatus? = null): OrdersFragment {
            val f = OrdersFragment()
            if (status != null) {
                f.arguments = Bundle().apply { putString(ARG_STATUS, status.name) }
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val statusName = arguments?.getString(ARG_STATUS)
        filterStatus = if (statusName != null) OrderStatus.valueOf(statusName) else null

        adapter = OrderAdapter(
            onConfirmManual = { order ->
                val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val webhookUrl = SupabaseConfig.getWebhookUrl(prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)) ?: ""
                val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
                if (webhookUrl.isNotEmpty() && order.taskId != null) {
                    android.widget.Toast.makeText(requireContext(), "جاري التأكيد اليدوي…", android.widget.Toast.LENGTH_SHORT).show()
                    val body = mutableMapOf<String, Any>(
                        "action" to "task_result",
                        "device_id" to HeartbeatManager.getDeviceId(requireContext()),
                        "task_id" to order.taskId,
                        "request_id" to order.requestId,
                        "status" to "success",
                        "result_data" to mapOf(
                            "manual_confirm" to true,
                            "confirmed_by" to "device_admin",
                            "amount" to order.expectedAmount,
                            "sender_phone" to (order.customerPhone ?: ""),
                            "transaction_id" to "manual-${System.currentTimeMillis()}",
                            "sms_body" to "تم تأكيد الطلب يدوياً من الجهاز"
                        )
                    )
                    WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { success, message, _ ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            if (success) {
                                AppState.updateOrderStatus(order.requestId, OrderStatus.CONFIRMED)
                                android.widget.Toast.makeText(requireContext(), "✅ تم التأكيد اليدوي", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(requireContext(), "فشل التأكيد: $message", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    android.widget.Toast.makeText(requireContext(), "task_id غير متوفر — اضغط إعادة الفحص أولاً", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onRescan = { order ->
                startTask(order, "🔍 جاري إعادة الفحص…")
            },
            onStartScan = { order ->
                startTask(order, "🔍 جاري بدء الفحص…")
            }
        )
        binding.ordersRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.ordersRecycler.adapter = adapter

        AppState.orders.observe(viewLifecycleOwner) { orders ->
            val filtered = if (filterStatus != null) orders.filter { it.status == filterStatus } else orders
            adapter.submitList(filtered)
            binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.text = if (filterStatus != null)
                "لا توجد طلبات في حالة ${filterStatus!!.label}"
            else "لا توجد طلبات بعد"
        }
    }

    private fun startTask(order: OrderItem, toast: String) {
        if (order.taskId == null) {
            android.widget.Toast.makeText(requireContext(), "لا يوجد task_id لهذا الطلب", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val webhookUrl = SupabaseConfig.getWebhookUrl(prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)) ?: ""
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        if (webhookUrl.isEmpty() || secret.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), "إعدادات الـ webhook غير مكتملة", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(requireContext(), toast, android.widget.Toast.LENGTH_SHORT).show()
        val task = TaskScanner.Task(
            taskId = order.taskId,
            requestId = order.requestId,
            amountRequested = order.expectedAmount,
            senderPhoneRequested = order.customerPhone,
            senderNameRequested = null,
            fingerprintAmount = null,
            creditsAmount = order.creditsRequested?.toDouble(),
            orderNumber = order.orderNumber,
            creditsRequested = order.creditsRequested,
            customerEmail = order.customerEmail,
            customerPhone = order.customerPhone,
            paymentMethod = order.paymentMethod,
            requestCreatedAt = null,
            paymentOrderId = null,
            orderExpiresAt = null
        )
        SmsMonitorService.forceScanTask(requireContext(), task)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
