package com.naderai.smsreader

import android.content.res.ColorStateList
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
            val snap = order.resolvedSnapshot()

            // رقم الطلب
            val label = if (snap.orderNumber != null) "طلب شحن #${snap.orderNumber}" else "طلب #${order.requestId.take(8)}"
            binding.orderIdText.text = label

            // المبلغ
            binding.orderAmountText.text = "%.2f".format(snap.expectedAmount)
            binding.orderCurrencyText.text = "جنيه"

            // الكريدت
            if (snap.creditsRequested != null && snap.creditsRequested > 0) {
                binding.orderCreditsText.visibility = View.VISIBLE
                binding.orderCreditsText.text = "${snap.creditsRequested} Credit"
            } else {
                binding.orderCreditsText.visibility = View.GONE
            }

            // حالة الطلب
            binding.orderStatusBadge.text = "${statusIcon(order.status)} ${order.status.label}"
            try {
                val color = Color.parseColor(order.status.color)
                binding.orderStatusBadge.backgroundTintList = ColorStateList.valueOf(color)
            } catch (_: Exception) {}

            // العميل (اسم صاحب الحساب من الموقع)
            binding.orderCustomerName.text = snap.customerName?.takeIf { it.isNotEmpty() } ?: "—"
            binding.orderCustomerEmail.text = snap.customerEmail?.takeIf { it.isNotEmpty() } ?: "—"

            // رقم المحوّل (senderPhoneRequested) — محفظة العميل التي يجب التحويل منها
            val senderPhone = snap.senderPhoneRequested?.trim()
            if (senderPhone.isNullOrEmpty()) {
                binding.orderSenderPhone.text = "غير محدد"
                binding.orderSenderPhone.alpha = 0.55f
                // تسجيل بيانات ناقصة للتشخيص
                OrderDiagnosticsLog.log(
                    OrderDiagnosticsLog.EventType.DATA_INCOMPLETE,
                    order.orderNumber, order.requestId,
                    details = "sender_phone_requested=null | order=${order.requestId}"
                )
            } else {
                binding.orderSenderPhone.text = senderPhone
                binding.orderSenderPhone.alpha = 1f
            }
            binding.orderSenderName.text = order.senderNameFound?.takeIf { it.isNotEmpty() }
                ?: snap.senderNameRequested?.takeIf { it.isNotEmpty() }
                ?: "غير محدد"

            // طريقة الدفع
            binding.orderPaymentMethod.text = when (snap.paymentMethod) {
                "vodafone_cash" -> "فودافون كاش"
                "instapay" -> "إنستاباي"
                else -> snap.paymentMethod ?: "—"
            }

            // تاريخ الطلب
            binding.orderCreatedAt.text = formatTime(order.createdAt)

            // مطابقة الرسالة
            if (order.status == OrderStatus.MATCHED || order.status == OrderStatus.COMPLETED || order.status == OrderStatus.CONFIRMED) {
                binding.orderMatchStatus.visibility = View.VISIBLE
                binding.orderMatchStatus.text = "✓ تم العثور على التحويل"
                binding.orderMatchAmount.text = "%.2f".format(order.amountFound ?: 0.0)
                binding.orderMatchAmount.visibility = View.VISIBLE
            } else if (order.status == OrderStatus.SCANNING || order.status == OrderStatus.MATCHING) {
                binding.orderMatchStatus.visibility = View.VISIBLE
                binding.orderMatchStatus.text = "🔎 جاري البحث..."
                binding.orderMatchAmount.visibility = View.GONE
            } else if (order.status == OrderStatus.AMOUNT_MISMATCH) {
                binding.orderMatchStatus.visibility = View.VISIBLE
                binding.orderMatchStatus.text = "⚠ المبلغ المُحوَّل لا يطابق"
                binding.orderMatchAmount.text = "%.2f".format(order.amountFound ?: 0.0)
                binding.orderMatchAmount.visibility = View.VISIBLE
            } else {
                binding.orderMatchStatus.visibility = View.GONE
                binding.orderMatchAmount.visibility = View.GONE
            }

            // سبب الفشل
            val userFriendlyReason: String? = when (order.status) {
                OrderStatus.NOT_FOUND -> "لم يتم العثور على معاملة مطابقة"
                OrderStatus.AMOUNT_MISMATCH -> "المبلغ المُحوَّل لا يطابق المطلوب"
                OrderStatus.FAILED -> order.failureReason ?: "فشل الفحص — تواصل مع الدعم"
                OrderStatus.EXPIRED -> "انتهت صلاحية الطلب"
                OrderStatus.ADMIN_OFFLINE -> "جهاز التأكيد غير متصل — سيُعالج الطلب لاحقاً"
                else -> null
            }
            if (userFriendlyReason != null) {
                binding.orderFailureReason.visibility = View.VISIBLE
                binding.orderFailureReason.text = userFriendlyReason
            } else {
                binding.orderFailureReason.visibility = View.GONE
            }

            // رقم العملية
            val txText = order.transactionId?.takeIf { it.isNotEmpty() && it != "null" }
            if (txText != null) {
                binding.orderTxId.visibility = View.VISIBLE
                binding.orderTxId.text = "رقم العملية: $txText"
            } else {
                binding.orderTxId.visibility = View.GONE
            }

            // عمر رسالة الـ SMS — يُظهر متى وصلت الرسالة التي تطابق الطلب
            val smsAge = order.scannedAt?.let { formatSmsAge(it) }
            if (smsAge != null && txText != null) {
                binding.orderSmsAge.visibility = View.VISIBLE
                binding.orderSmsAge.text = "⏱ الرسالة وصلت: $smsAge"
            } else {
                binding.orderSmsAge.visibility = View.GONE
            }

            // حالة الفحص + عداد المحاولات
            if (order.status in setOf(OrderStatus.SCANNING, OrderStatus.MATCHING, OrderStatus.PENDING,
                    OrderStatus.MANUAL_REVIEW, OrderStatus.WAITING_FOR_VERIFICATION, OrderStatus.REOPENED)) {
                binding.orderScanProgress.visibility = View.VISIBLE
                val remaining = order.maxAttempts - order.scanAttempt
                val attemptText = when {
                    order.status == OrderStatus.ADMIN_OFFLINE -> "📵 الجهاز غير متصل — سيُفحص عند الاتصال"
                    order.status == OrderStatus.REOPENED -> "🔓 أُعيد فتحه — ينتظر إرساله للجهاز"
                    order.status == OrderStatus.WAITING_FOR_VERIFICATION -> "⏳ ينتظر الجهاز…"
                    order.status == OrderStatus.MANUAL_REVIEW -> "⚠ يحتاج مراجعة يدوية"
                    order.status == OrderStatus.EXPIRED -> "انتهت صلاحية الطلب"
                    order.scanAttempt > 0 -> "محاولة الفحص: ${order.scanAttempt}/${order.maxAttempts} (متبقي $remaining)"
                    else -> "في انتظار بدء الفحص…"
                }
                val countdownText = if (order.nextScanCountdown > 0 && order.status == OrderStatus.SCANNING)
                    " • التالية بعد ${order.nextScanCountdown}ث"
                else ""
                binding.orderScanProgress.text = attemptText + countdownText
            } else {
                binding.orderScanProgress.visibility = View.GONE
            }

            // أزرار الإجراءات
            val showActions = order.status in setOf(
                OrderStatus.PENDING, OrderStatus.SCANNING, OrderStatus.MATCHING, OrderStatus.MANUAL_REVIEW,
                OrderStatus.AMOUNT_MISMATCH, OrderStatus.NOT_FOUND, OrderStatus.FAILED,
                OrderStatus.ADMIN_OFFLINE, OrderStatus.WAITING_FOR_VERIFICATION, OrderStatus.REOPENED
            )
            binding.actionDivider.visibility = if (showActions) View.VISIBLE else View.GONE
            binding.actionButtons.visibility = if (showActions) View.VISIBLE else View.GONE

            binding.btnStartScan.visibility = if (order.status == OrderStatus.PENDING) View.VISIBLE else View.GONE
            binding.btnRescan.visibility = if (order.status != OrderStatus.PENDING && !order.status.isTerminal()) View.VISIBLE else View.GONE
            binding.btnConfirmManual.visibility = if (!order.status.isTerminal()) View.VISIBLE else View.GONE

            binding.btnStartScan.setOnClickListener { onStartScan?.invoke(order) }
            binding.btnConfirmManual.setOnClickListener { onConfirmManual?.invoke(order) }
            binding.btnRescan.setOnClickListener { onRescan?.invoke(order) }
        }

        private fun statusIcon(status: OrderStatus): String = when (status) {
            OrderStatus.NEW -> "🟣"
            OrderStatus.RECEIVED -> "📥"
            OrderStatus.PENDING -> "⏳"
            OrderStatus.SCANNING -> "🔍"
            OrderStatus.MATCHING -> "🔎"
            OrderStatus.SMS_FOUND -> "📩"          // Phase-2
            OrderStatus.REVIEWING -> "🧐"           // Phase-2
            OrderStatus.MATCHED -> "🎯"
            OrderStatus.WAITING_CONFIRMATION -> "⏳"
            OrderStatus.CONFIRMED -> "✅"
            OrderStatus.COMPLETED -> "✅"
            OrderStatus.AMOUNT_MISMATCH -> "⚠"
            OrderStatus.MANUAL_REVIEW -> "👁"
            OrderStatus.NOT_FOUND -> "❌"
            OrderStatus.FAILED -> "🚫"
            OrderStatus.DUPLICATE -> "🔁"
            OrderStatus.EXPIRED -> "⌛"
            OrderStatus.CANCELLED -> "🚫"           // Phase-3
            OrderStatus.REJECTED -> "❌"             // Phase-3
            // v2 lifecycle statuses
            OrderStatus.ADMIN_OFFLINE -> "📵"
            OrderStatus.WAITING_FOR_VERIFICATION -> "⏳"
            OrderStatus.REOPENED -> "🔓"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("dd/MM hh:mm a", Locale("ar")).format(Date(ts))

    /** يعرض متى وصلت رسالة الـ SMS بشكل نسبي ذكي */
    private fun formatSmsAge(ts: Long): String {
        val diffMs = System.currentTimeMillis() - ts
        val diffSec  = diffMs / 1000
        val diffMin  = diffSec / 60
        val diffHour = diffMin / 60
        val diffDay  = diffHour / 24
        return when {
            diffSec  < 60   -> "منذ أقل من دقيقة"
            diffMin  < 60   -> "منذ $diffMin دقيقة"
            diffHour < 24   -> "منذ $diffHour ساعة"
            diffDay  == 1L  -> "منذ يوم واحد"
            diffDay  < 7    -> "منذ $diffDay أيام"
            else            -> SimpleDateFormat("dd/MM HH:mm", Locale("ar")).format(Date(ts))
        }
    }
}
