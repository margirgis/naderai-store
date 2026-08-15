package com.naderai.smsreader

import androidx.lifecycle.MutableLiveData

/**
 * Singleton observable state — shared between all fragments.
 */
object AppState {
    // Connection & registration
    val isConnected = MutableLiveData(false)
    val isRegistered = MutableLiveData<Boolean?>(null)
    val connectionMessage = MutableLiveData("في انتظار الإعدادات...")
    val registrationMessage = MutableLiveData("")
    val lastSyncTime = MutableLiveData<Long?>(null)
    val lastError = MutableLiveData<String?>(null)

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

    // Notifications
    val notifications = MutableLiveData<List<DeviceNotification>>(emptyList())
    val unreadNotificationCount = MutableLiveData(0)

    fun updateFromHeartbeat(connected: Boolean, message: String) {
        isConnected.postValue(connected)
        connectionMessage.postValue(message)
        if (connected) lastSyncTime.postValue(System.currentTimeMillis())
        if (!connected) lastError.postValue(message)
    }

    fun updateRegistrationStatus(registered: Boolean, message: String) {
        isRegistered.postValue(registered)
        registrationMessage.postValue(message)
        if (!registered) lastError.postValue(message)
    }

    fun addNotification(notification: DeviceNotification) {
        val current = notifications.value?.toMutableList() ?: mutableListOf()
        current.add(0, notification)
        // Keep max 100
        if (current.size > 100) current.removeAt(current.size - 1)
        notifications.postValue(current)
        val unread = current.count { !it.isRead }
        unreadNotificationCount.postValue(unread)
    }

    fun markAllNotificationsRead() {
        val updated = notifications.value?.map { it.copy(isRead = true) } ?: emptyList()
        notifications.postValue(updated)
        unreadNotificationCount.postValue(0)
    }

    fun onTaskResult(task: TaskScanner.Task, result: TaskScanner.ScanResult) {
        lastSmsScannedAt.postValue(System.currentTimeMillis())
        when (result) {
            is TaskScanner.ScanResult.Success -> {
                confirmedCount.postValue((confirmedCount.value ?: 0) + 1)
                lastFoundTransaction.postValue(result.message.transactionId)
                updateOrderStatus(task.requestId, OrderStatus.CONFIRMED)
                addNotification(DeviceNotification(
                    title = "تم العثور على العملية ✓",
                    message = "المبلغ: ${result.message.amount} — رقم العملية: ${result.message.transactionId ?: "—"}",
                    type = NotificationType.ORDER_CONFIRMED,
                    referenceId = task.requestId
                ))
            }
            is TaskScanner.ScanResult.NotFound -> {
                notFoundCount.postValue((notFoundCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.NOT_FOUND)
                addNotification(DeviceNotification(
                    title = "لم يتم العثور على العملية",
                    message = result.reason,
                    type = NotificationType.ORDER_NOT_FOUND,
                    referenceId = task.requestId
                ))
            }
            is TaskScanner.ScanResult.AmountMismatch -> {
                failedCount.postValue((failedCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.AMOUNT_MISMATCH)
                addNotification(DeviceNotification(
                    title = "مبلغ غير مطابق",
                    message = "المطلوب: ${result.expectedAmount} — الموجود: ${result.foundAmount}",
                    type = NotificationType.ORDER_MISMATCH,
                    referenceId = task.requestId
                ))
            }
            is TaskScanner.ScanResult.Failure -> {
                failedCount.postValue((failedCount.value ?: 0) + 1)
                updateOrderStatus(task.requestId, OrderStatus.FAILED)
                addNotification(DeviceNotification(
                    title = "فشل الفحص",
                    message = result.reason,
                    type = NotificationType.ERROR,
                    referenceId = task.requestId
                ))
            }
        }
    }

    fun updateOrderStatus(requestId: String, status: OrderStatus) {
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

// ── Notification model ────────────────────────────────────────────────────
enum class NotificationType { CONNECTED, ERROR, ORDER_NEW, ORDER_CONFIRMED, ORDER_NOT_FOUND, ORDER_MISMATCH, TEST_SUCCESS, TEST_RECEIVED, SERVER_DOWN }

data class DeviceNotification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val type: NotificationType,
    val referenceId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

// ── Order status ──────────────────────────────────────────────────────────
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
    val transactionId: String? = null,
    // تفاصيل كاملة من الموقع
    val orderNumber: Long? = null,
    val creditsRequested: Int? = null,
    val customerEmail: String? = null,
    val customerPhone: String? = null,
    val paymentMethod: String? = null,
    // مرجع للمهمة للعمليات اليدوية
    val taskId: String? = null
)
