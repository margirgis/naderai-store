package com.naderai.smsreader

import androidx.lifecycle.MutableLiveData

/**
 * Singleton observable state for the whole app — shared between fragments.
 */
object AppState {
    // Connection
    val isConnected = MutableLiveData(false)
    val connectionMessage = MutableLiveData("في انتظار الإعدادات...")
    val lastSyncTime = MutableLiveData<Long?>(null)

    // Stats
    val pendingCount = MutableLiveData(0)
    val confirmedCount = MutableLiveData(0)
    val failedCount = MutableLiveData(0)
    val notFoundCount = MutableLiveData(0)

    // Last events
    val lastSmsScannedAt = MutableLiveData<Long?>(null)
    val lastFoundTransaction = MutableLiveData<String?>(null)

    // Orders list
    val orders = MutableLiveData<List<OrderItem>>(emptyList())

    // Pending tasks from heartbeat
    val pendingTasks = MutableLiveData<List<TaskScanner.Task>>(emptyList())

    fun updateFromHeartbeat(connected: Boolean, message: String) {
        isConnected.postValue(connected)
        connectionMessage.postValue(message)
        if (connected) lastSyncTime.postValue(System.currentTimeMillis())
    }

    fun onTaskResult(task: TaskScanner.Task, result: TaskScanner.ScanResult) {
        lastSmsScannedAt.postValue(System.currentTimeMillis())
        when (result) {
            is TaskScanner.ScanResult.Success -> {
                confirmedCount.postValue((confirmedCount.value ?: 0) + 1)
                lastFoundTransaction.postValue(result.message.transactionId)
                // Update order status in list
                updateOrderStatus(task.requestId, OrderStatus.CONFIRMED)
            }
            is TaskScanner.ScanResult.NotFound -> {
                notFoundCount.postValue((notFoundCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.NOT_FOUND)
            }
            is TaskScanner.ScanResult.AmountMismatch -> {
                failedCount.postValue((failedCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.AMOUNT_MISMATCH)
            }
            is TaskScanner.ScanResult.Failure -> {
                failedCount.postValue((failedCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.FAILED)
            }
        }
    }

    private fun updateOrderStatus(requestId: String, status: OrderStatus) {
        val current = orders.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.requestId == requestId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(status = status, updatedAt = System.currentTimeMillis())
            orders.postValue(current)
        }
    }

    fun addOrUpdateOrder(order: OrderItem) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val idx = current.indexOfFirst { it.requestId == order.requestId }
        if (idx >= 0) current[idx] = order else current.add(0, order)
        pendingCount.postValue(current.count { it.status == OrderStatus.PENDING || it.status == OrderStatus.SCANNING })
        orders.postValue(current)
    }
}

enum class OrderStatus(val label: String, val color: String) {
    PENDING("قيد المراجعة", "#F59E0B"),
    SCANNING("جاري البحث", "#3B82F6"),
    FOUND("تم العثور عليه", "#10B981"),
    CONFIRMED("تم التأكيد", "#059669"),
    AMOUNT_MISMATCH("مبلغ غير مطابق", "#EF4444"),
    NOT_FOUND("لم يتم العثور", "#6B7280"),
    FAILED("فشل", "#DC2626"),
    DUPLICATE("مكرر", "#8B5CF6");

    companion object {
        fun fromString(s: String?): OrderStatus = when (s) {
            "scanning" -> SCANNING
            "found" -> FOUND
            "confirmed", "approved" -> CONFIRMED
            "amount_mismatch" -> AMOUNT_MISMATCH
            "not_found" -> NOT_FOUND
            "failed", "rejected" -> FAILED
            "duplicate" -> DUPLICATE
            else -> PENDING
        }
    }
}

data class OrderItem(
    val requestId: String,
    val orderLabel: String,
    val expectedAmount: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val failureReason: String? = null,
    val transactionId: String? = null
)
