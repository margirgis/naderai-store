package com.naderai.smsreader

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naderai.smsreader.databinding.ItemOrderBinding
import java.text.SimpleDateFormat
import java.util.*

class OrderAdapter : ListAdapter<OrderItem, OrderAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OrderItem>() {
            override fun areItemsTheSame(a: OrderItem, b: OrderItem) = a.requestId == b.requestId
            override fun areContentsTheSame(a: OrderItem, b: OrderItem) = a == b
        }
    }

    inner class VH(private val binding: ItemOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(order: OrderItem) {
            binding.orderIdText.text = "طلب #${order.requestId.take(8)}…"
            binding.orderAmountText.text = "%.2f جنيه".format(order.expectedAmount)
            binding.orderStatusBadge.text = order.status.label
            try { binding.orderStatusBadge.setBackgroundColor(Color.parseColor(order.status.color)) }
            catch (_: Exception) {}
            binding.orderCreatedAt.text = formatTime(order.createdAt)
            binding.orderUpdatedAt.text = "آخر تحديث: ${formatTime(order.updatedAt)}"

            if (!order.failureReason.isNullOrEmpty()) {
                binding.orderFailureReason.visibility = View.VISIBLE
                binding.orderFailureReason.text = "السبب: ${order.failureReason}"
            } else {
                binding.orderFailureReason.visibility = View.GONE
            }

            if (!order.transactionId.isNullOrEmpty()) {
                binding.orderTxId.visibility = View.VISIBLE
                binding.orderTxId.text = "رقم العملية: ${order.transactionId}"
            } else {
                binding.orderTxId.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ts))
}
