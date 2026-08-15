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
    private val onRescan: ((OrderItem) -> Unit)? = null
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

            // بيانات العميل
            binding.orderCustomerPhone.text = order.customerPhone?.takeIf { it.isNotEmpty() } ?: "رقم غير معروف"
            binding.orderCustomerEmail.text = order.customerEmail?.takeIf { it.isNotEmpty() } ?: "—"

            // طريقة الدفع
            binding.orderPaymentMethod.text = when (order.paymentMethod) {
                "vodafone_cash" -> "💳 فودافون كاش"
                "instapay" -> "💳 إنستاباي"
                else -> order.paymentMethod ?: "—"
            }

            // تاريخ الطلب
            binding.orderCreatedAt.text = formatTime(order.createdAt)

            // سبب الفشل
            if (!order.failureReason.isNullOrEmpty()) {
                binding.orderFailureReason.visibility = View.VISIBLE
                binding.orderFailureReason.text = "⚠️ ${order.failureReason}"
            } else {
                binding.orderFailureReason.visibility = View.GONE
            }

            // رقم العملية
            if (!order.transactionId.isNullOrEmpty()) {
                binding.orderTxId.visibility = View.VISIBLE
                binding.orderTxId.text = "رقم العملية: ${order.transactionId}"
            } else {
                binding.orderTxId.visibility = View.GONE
            }

            // حالة الفحص + عداد المحاولات (يظهر للطلبات المعلقة وجاري فحصها)
            if (order.status == OrderStatus.SCANNING || order.status == OrderStatus.PENDING) {
                binding.orderScanProgress.visibility = View.VISIBLE
                val remaining = order.maxAttempts - order.scanAttempt
                val attemptText = when {
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

            // أزرار الإجراءات لحالات تحتاج تدخل يدوي
            val showActions = order.status in listOf(
                OrderStatus.AMOUNT_MISMATCH, OrderStatus.NOT_FOUND, OrderStatus.FAILED
            )
            binding.actionDivider.visibility = if (showActions) View.VISIBLE else View.GONE
            binding.actionButtons.visibility = if (showActions) View.VISIBLE else View.GONE

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
