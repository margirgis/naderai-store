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
                if (order.taskId == null) {
                    android.widget.Toast.makeText(requireContext(), "task_id غير متوفر — اضغط إعادة الفحص أولاً", android.widget.Toast.LENGTH_SHORT).show()
                    return@OrderAdapter
                }
                val context = requireContext()
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val webhookUrl = SupabaseConfig.getWebhookUrl(prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)) ?: ""
                val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""

                android.widget.Toast.makeText(context, "جاري التأكيد اليدوي…", android.widget.Toast.LENGTH_SHORT).show()

                val manualTask = TaskScanner.Task(
                    taskId = order.taskId,
                    requestId = order.requestId,
                    amountRequested = order.expectedAmount,
                    senderPhoneRequested = order.customerPhone,
                    senderNameRequested = order.customerName,
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
                    senderPhone = order.customerPhone,
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
                    secret = secret
                ) { success ->
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        if (success) {
                            AppState.onTaskResult(manualTask, result)
                            AppState.updateOrderStatus(order.requestId, OrderStatus.CONFIRMED)
                            android.widget.Toast.makeText(context, "✅ تم التأكيد اليدوي", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "فشل التأكيد — تحقق من اتصال الإنترنت أو جلسة الأدمن", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
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
        val task = TaskScanner.Task(
            taskId = order.taskId,
            requestId = order.requestId,
            amountRequested = order.expectedAmount,
            senderPhoneRequested = order.customerPhone,
            senderNameRequested = order.customerName,
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
