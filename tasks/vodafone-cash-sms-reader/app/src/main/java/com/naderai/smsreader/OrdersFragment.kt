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
    private var filterStatuses: List<OrderStatus> = emptyList()

    companion object {
        private const val ARG_STATUSES = "statuses"
        fun newInstance(statuses: List<OrderStatus>? = null): OrdersFragment {
            val f = OrdersFragment()
            if (!statuses.isNullOrEmpty()) {
                f.arguments = Bundle().apply {
                    putStringArray(ARG_STATUSES, statuses.map { it.name }.toTypedArray())
                }
            }
            return f
        }

        /** للتوافق مع الأكواد القديمة التي تمرر حالة واحدة */
        fun newInstance(status: OrderStatus?): OrdersFragment = newInstance(status?.let { listOf(it) })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val names = arguments?.getStringArray(ARG_STATUSES)
        filterStatuses = names?.mapNotNull { runCatching { OrderStatus.valueOf(it) }.getOrNull() } ?: emptyList()

        adapter = OrderAdapter(
            onConfirmManual = { order ->
                if (order.taskId == null) {
                    android.widget.Toast.makeText(requireContext(), "task_id غير متوفر — اضغط إعادة الفحص أولاً", android.widget.Toast.LENGTH_SHORT).show()
                    return@OrderAdapter
                }
                val context = requireContext()
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val webhookUrl = SupabaseConfig.getWebhookUrl(prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)) ?: ""
                val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""

                android.widget.Toast.makeText(context, "جاري التأكيد اليدوي…", android.widget.Toast.LENGTH_SHORT).show()

                // senderPhoneRequested هو رقم محفظة المُحوِّل — يختلف تماماً عن customerPhone
                val manualTask = TaskScanner.Task(
                    taskId = order.taskId,
                    requestId = order.requestId,
                    amountRequested = order.expectedAmount,
                    senderPhoneRequested = order.senderPhoneRequested,
                    senderNameRequested = order.senderNameRequested,
                    fingerprintAmount = order.expectedAmount,
                    creditsAmount = order.creditsRequested?.toDouble(),
                    orderNumber = order.orderNumber,
                    creditsRequested = order.creditsRequested,
                    customerEmail = order.customerEmail,
                    customerPhone = order.customerPhone,
                    paymentMethod = order.paymentMethod,
                    requestCreatedAt = null,
                    paymentOrderId = order.paymentOrderId,
                    orderExpiresAt = order.orderExpiresAt
                )

                val now = System.currentTimeMillis()
                val manualMessage = TaskScanner.ScannedMessage(
                    sender = "manual_confirm",
                    senderPhone = order.senderPhoneRequested,
                    senderName = order.customerName,
                    amount = order.expectedAmount,
                    transactionId = "manual-${order.requestId}-${now}",
                    body = "تم تأكيد الطلب يدوياً من الجهاز",
                    date = now,
                    amountMatch = true,
                    phoneMatch = true,
                    score = 5,
                    receiverWallet = null
                )
                val result = TaskScanner.ScanResult.Success(manualMessage)

                TaskScanner.sendTaskResult(
                    context = context,
                    task = manualTask,
                    result = result,
                    webhookUrl = webhookUrl,
                    secret = secret,
                    onSent = { success ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            if (success) {
                                AppState.onTaskResult(manualTask, result)
                                AppState.updateOrderStatus(order.requestId, OrderStatus.COMPLETED)
                                android.widget.Toast.makeText(context, "✅ تم التأكيد اليدوي", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "فشل التأكيد — تحقق من اتصال الإنترنت أو جلسة الأدمن", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        Unit
                    }
                )
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
            val filtered = if (filterStatuses.isNotEmpty()) orders.filter { it.status in filterStatuses } else orders
            adapter.submitList(filtered)
            binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            binding.emptyText.text = if (filterStatuses.isNotEmpty()) {
                val labels = filterStatuses.joinToString(" / ") { it.label }
                "لا توجد طلبات في حالة: $labels"
            } else "لا توجد طلبات بعد"
        }
    }

    private fun startTask(order: OrderItem, toast: String) {
        if (order.taskId == null) {
            android.widget.Toast.makeText(requireContext(), "لا يوجد task_id لهذا الطلب", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val context = requireContext()
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val webhookUrl = SupabaseConfig.getWebhookUrl(prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)) ?: ""
        val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
        val adminLoggedIn = AdminSession.isLoggedIn(context)

        if (!adminLoggedIn && (webhookUrl.isEmpty() || secret.isEmpty())) {
            android.widget.Toast.makeText(context, "إعدادات الـ webhook غير مكتملة", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()

        // PHASE 12: رفض sender_phone إذا كان فارغاً أو يساوي receiver_wallet
        val senderPhone = order.senderPhoneRequested?.trim().orEmpty()
        if (senderPhone.isEmpty()) {
            android.widget.Toast.makeText(context, "رقم المُحوِّل غير متوفر — لا يمكن بدء الفحص", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (senderPhone == "01097273680") {
            android.widget.Toast.makeText(context, "رقم محفظة الاستلام لا يُستخدم كرقم مُحوِّل", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // senderPhoneRequested هو رقم محفظة المُحوِّل — يختلف تماماً عن customerPhone
        val task = TaskScanner.Task(
            taskId = order.taskId,
            requestId = order.requestId,
            amountRequested = order.expectedAmount,
            senderPhoneRequested = order.senderPhoneRequested,
            senderNameRequested = order.senderNameRequested,
            fingerprintAmount = order.expectedAmount,
            creditsAmount = order.creditsRequested?.toDouble(),
            orderNumber = order.orderNumber,
            creditsRequested = order.creditsRequested,
            customerEmail = order.customerEmail,
            customerPhone = order.customerPhone,
            paymentMethod = order.paymentMethod,
            requestCreatedAt = null,
            paymentOrderId = order.paymentOrderId,
            orderExpiresAt = order.orderExpiresAt
        )
        SmsMonitorService.forceScanTask(context, task)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
