package com.naderai.smsreader

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naderai.smsreader.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.*

class OrderAdapter(
    private val onConfirmManual: ((OrderItem) -> Unit)? = null,
    private val onRescan: ((OrderItem) -> Unit)? = null,
    private val onStartScan: ((OrderItem) -> Unit)? = null
) : ListAdapter<OrderItem, OrderAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OrderItem>() {
            override fun areItemsTheSame(a: OrderItem, b: OrderItem) = a.requestId == b.requestId
            override fun areContentsTheSame(a: OrderItem, b: OrderItem) = a == b
        }
    }

    inner class VH(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: OrderItem) {
            // رقم الطلب
            val label = if (order.orderNumber != null) "طلب #${order.orderNumber}" else "طلب #${order.requestId.take(8)}…"
            binding.orderIdText.text = label

            // المبلغ
            binding.orderAmountText.text = "%.2f جنيه".format(order.expectedAmount)

            // الكريدت
            if (order.creditsRequested != null && order.creditsRequested > 0) {
                binding.orderCreditsText.visibility = View.VISIBLE
                binding.orderCreditsText.text = "🎯 ${order.creditsRequested} كريدت"
            } else {
                binding.orderCreditsText.visibility = View.GONE
            }

            // حالة الطلب
            binding.orderStatusBadge.text = order.status.label
            try { binding.orderStatusBadge.setBackgroundColor(Color.parseColor(order.status.color)) }
            catch (_: Exception) {}

            // بيانات المُحوِّل (رقم المرسل) — هذا هو الرقم الذي حوّل منه العميل
            // customerPhone هو رقم صاحب الحساب (رقم تسجيل الدخول)، يختلف عن رقم المحفظة المُحوِّلة
            val senderDisplay = order.senderPhoneRequested?.takeIf { it.isNotEmpty() }
                ?: order.customerPhone?.takeIf { it.isNotEmpty() }
                ?: "رقم غير معروف"
            binding.orderCustomerPhone.text = senderDisplay

            // اسم صاحب الحساب الحقيقي (من profiles.full_name) — ليس اسم المُحوِّل
            val nameDisplay = order.customerName?.takeIf { it.isNotEmpty() }
                ?: order.customerEmail?.takeIf { it.isNotEmpty() }?.substringBefore('@')
                ?: "—"
            binding.orderCustomerEmail.text = "$nameDisplay • ${order.customerEmail?.takeIf { it.isNotEmpty() } ?: "—"}"

            // طريقة الدفع
            binding.orderPaymentMethod.text = when (order.paymentMethod) {
                "vodafone_cash" -> "💳 فودافون كاش"
                "instapay" -> "💳 إنستاباي"
                else -> order.paymentMethod ?: "—"
            }

            // تاريخ الطلب
            binding.orderCreatedAt.text = formatTime(order.createdAt)

            // سبب الفشل — رسالة مستخدم لطيفة بدون تفاصيل تقنية
            // لا نعرض "تم العثور على 734 رسالة لكن لا توجد مطابقة تامة" أو ما شابهها
            val userFriendlyReason: String? = when (order.status) {
                OrderStatus.NOT_FOUND      -> "لم يتم العثور على معاملة مطابقة"
                OrderStatus.AMOUNT_MISMATCH-> "المبلغ المُحوَّل لا يطابق المطلوب"
                OrderStatus.FAILED         -> "فشل الفحص — تواصل مع الدعم"
                else -> null  // لا نعرض شيئاً للحالات الأخرى
            }
            if (userFriendlyReason != null) {
                binding.orderFailureReason.visibility = View.VISIBLE
                binding.orderFailureReason.text = "⚠️ $userFriendlyReason"
            } else {
                binding.orderFailureReason.visibility = View.GONE
            }

            // رقم العملية — يُخفى لو null أو "null"
            val txText = order.transactionId?.takeIf { it.isNotEmpty() && it != "null" }
            if (txText != null) {
                binding.orderTxId.visibility = View.VISIBLE
                binding.orderTxId.text = "رقم العملية: $txText"
            } else {
                binding.orderTxId.visibility = View.GONE
            }

            // حالة الفحص + عداد المحاولات (يظهر للطلبات المعلقة وجاري فحصها)
            if (order.status in setOf(OrderStatus.SCANNING, OrderStatus.PENDING, OrderStatus.MANUAL_REVIEW)) {
                binding.orderScanProgress.visibility = View.VISIBLE
                val remaining = order.maxAttempts - order.scanAttempt
                val attemptText = when {
                    order.status == OrderStatus.MANUAL_REVIEW -> "⚠️ يحتاج مراجعة يدوية"
                    order.status == OrderStatus.EXPIRED -> "انتهت صلاحية الطلب"
                    order.scanAttempt > 0 -> "🔍 المحاولة ${order.scanAttempt}/${order.maxAttempts} (متبقي $remaining)"
                    else -> "⏳ في انتظار بدء الفحص…"
                }
                val countdownText = if (order.nextScanCountdown > 0 && order.status == OrderStatus.SCANNING)
                    " • التالية بعد ${order.nextScanCountdown}ث"
                else ""
                binding.orderScanProgress.text = attemptText + countdownText
            } else {
                binding.orderScanProgress.visibility = View.GONE
            }

            // أزرار الإجراءات: يبدأ الفحص للطلبات المعلقة، وتأكيد/إعادة للحالات النهائية
            // الطلبات المنتهية الصلاحية لا تسمح بتأكيد يدوي
            val showActions = order.status in listOf(
                OrderStatus.PENDING, OrderStatus.SCANNING, OrderStatus.MANUAL_REVIEW,
                OrderStatus.AMOUNT_MISMATCH, OrderStatus.NOT_FOUND, OrderStatus.FAILED
            )
            binding.actionDivider.visibility = if (showActions) View.VISIBLE else View.GONE
            binding.actionButtons.visibility = if (showActions) View.VISIBLE else View.GONE

            binding.btnStartScan.visibility = if (order.status == OrderStatus.PENDING) View.VISIBLE else View.GONE
            binding.btnRescan.visibility = if (order.status != OrderStatus.PENDING) View.VISIBLE else View.GONE

            binding.btnStartScan.setOnClickListener { onStartScan?.invoke(order) }
            binding.btnConfirmManual.setOnClickListener { onConfirmManual?.invoke(order) }
            binding.btnRescan.setOnClickListener { onRescan?.invoke(order) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("dd/MM hh:mm a", Locale("ar")).format(Date(ts))
}
