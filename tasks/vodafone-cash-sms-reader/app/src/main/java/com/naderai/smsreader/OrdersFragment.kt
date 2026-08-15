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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val body = org.json.JSONObject().apply {
                                put("action", "task_result")
                                put("task_id", order.taskId)
                                put("request_id", order.requestId)
                                put("result", "manual_confirmed")
                                put("manual_confirm", true)
                                put("confirmed_by", "device_admin")
                            }
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val mediaType = "application/json".toMediaType()
                            val req = okhttp3.Request.Builder()
                                .url(webhookUrl)
                                .addHeader("x-device-secret", secret)
                                .post(body.toString().toRequestBody(mediaType))
                                .build()
                            val resp = client.newCall(req).execute()
                            val success = resp.isSuccessful
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (success) {
                                    AppState.updateOrderStatus(order.requestId, OrderStatus.CONFIRMED)
                                    android.widget.Toast.makeText(requireContext(), "✅ تم التأكيد اليدوي", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(requireContext(), "فشل التأكيد: ${resp.code}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(requireContext(), "خطأ: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
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
            requestCreatedAt = null
        )
        SmsMonitorService.forceScanTask(requireContext(), task)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
