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
                val context = requireContext()
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val supabaseUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)

                // ── مسار جديد: admin-manual-confirm Edge Function ────────────────
                val paymentOrderId = order.paymentOrderId
                if (paymentOrderId != null) {
                    android.widget.Toast.makeText(context, "جاري التأكيد اليدوي…", android.widget.Toast.LENGTH_SHORT).show()
                    val adminSession = AdminSession.isLoggedIn(context)
                    if (!adminSession) {
                        android.widget.Toast.makeText(context, "يجب تسجيل دخول الأدمن أولاً", android.widget.Toast.LENGTH_LONG).show()
                        return@OrderAdapter
                    }
                    val accessToken  = AdminSession.accessToken(context)  ?: ""
                    val refreshToken = AdminSession.refreshToken(context) ?: ""
                    val confirmUrl = SupabaseConfig.getAdminManualConfirmUrl(supabaseUrl) ?: ""
                    // ✅ FIX: إرسال access_token كـ Bearer token في Authorization header
                    // admin-manual-confirm يستخدم requireAdmin الذي يتحقق من Authorization header
                    WebhookSender.sendAdminJson(
                        url = confirmUrl,
                        body = mapOf(
                            "order_id"          to paymentOrderId,
                            "topup_request_id"  to (order.requestId),
                            "reason"            to "تأكيد يدوي من الجهاز",
                            "access_token"      to accessToken,
                            "refresh_token"     to refreshToken
                        ),
                        adminToken = accessToken  // ✅ يُرسَل كـ Bearer في Authorization header
                    ) { success, message, responseBody ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            android.util.Log.d("OrdersFragment", "manual-confirm response: success=$success msg=$message body=$responseBody")
                            if (success) {
                                AppState.updateOrderStatus(order.requestId, OrderStatus.COMPLETED)
                                android.widget.Toast.makeText(context, "✅ تم التأكيد اليدوي", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "فشل التأكيد: $message", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        Unit
                    }
                    return@OrderAdapter
                }

                // ── مسار قديم (fallback): wallet-auto-confirm عبر task result ────
                if (order.taskId == null) {
                    android.widget.Toast.makeText(context, "task_id غير متوفر — اضغط إعادة الفحص أولاً", android.widget.Toast.LENGTH_SHORT).show()
                    return@OrderAdapter
                }
                val webhookUrl = SupabaseConfig.getWebhookUrl(supabaseUrl) ?: ""
                val secret = prefs.getString(MainActivity.KEY_SECRET, null) ?: ""
                android.widget.Toast.makeText(context, "جاري التأكيد اليدوي…", android.widget.Toast.LENGTH_SHORT).show()
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
                                android.widget.Toast.makeText(context, "فشل التأكيد — تحقق من الاتصال أو جلسة الأدمن", android.widget.Toast.LENGTH_LONG).show()
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

        // ✅ FIX: لا نرفض الطلب بسبب sender_phone الفارغ هنا
        // TaskScanner نفسه يتعامل مع الحالة: إذا لم يوجد sender_phone، يفحص بناءً على المبلغ فقط
        // الرفض المبكر كان يمنع الطلبات الجديدة من الوصول للفحص
        val senderPhone = order.senderPhoneRequested?.trim().orEmpty()
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
