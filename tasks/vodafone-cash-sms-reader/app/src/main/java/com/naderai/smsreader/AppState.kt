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

    /**
     * يدمج قائمة طلبات جديدة من السيرفر مع الطلبات المحلية.
     * لا يحذف الطلبات القديمة، ولا يعيد الحالات النهائية إلى قيد المراجعة.
     */
    fun mergeOrders(newOrders: List<OrderItem>) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val currentMap = current.associateBy { it.requestId }.toMutableMap()

        for (order in newOrders) {
            val existing = currentMap[order.requestId]
            if (existing != null) {
                // لا نعيد الحالة النهائية إلى حالة مبدئية
                val isTerminal = existing.status in setOf(
                    OrderStatus.CONFIRMED, OrderStatus.NOT_FOUND,
                    OrderStatus.AMOUNT_MISMATCH, OrderStatus.FAILED, OrderStatus.DUPLICATE
                )
                // SCANNING ليست terminal — لو السيرفر أعاد نفس الطلب بحالة جديدة نقبلها
                val serverIsTerminal = order.status in setOf(
                    OrderStatus.CONFIRMED, OrderStatus.NOT_FOUND,
                    OrderStatus.AMOUNT_MISMATCH, OrderStatus.FAILED, OrderStatus.DUPLICATE
                )
                val mergedStatus = when {
                    // السيرفر جاب حالة نهائية → نثق بالسيرفر دائماً
                    serverIsTerminal -> order.status
                    // الجهاز عنده حالة نهائية → نحافظ عليها
                    isTerminal -> existing.status
                    // غير ذلك → نأخذ حالة السيرفر
                    else -> order.status
                }
                // نحافظ على البيانات الحساسة (task_id, expiry) إذا كانت موجودة في السيرفر
                currentMap[order.requestId] = order.copy(
                    status = mergedStatus,
                    updatedAt = order.updatedAt,
                    scanAttempt = if (existing.status == OrderStatus.SCANNING) existing.scanAttempt else order.scanAttempt,
                    maxAttempts = if (existing.maxAttempts > order.maxAttempts) existing.maxAttempts else order.maxAttempts,
                    nextScanCountdown = if (existing.status == OrderStatus.SCANNING) existing.nextScanCountdown else order.nextScanCountdown,
                    failureReason = order.failureReason ?: existing.failureReason,
                    transactionId = order.transactionId ?: existing.transactionId
                )
            } else {
                currentMap[order.requestId] = order
            }
        }

        val merged = currentMap.values.sortedByDescending { it.createdAt }
        orders.postValue(merged)
        pendingCount.postValue(merged.count { it.status == OrderStatus.PENDING || it.status == OrderStatus.SCANNING })
        confirmedCount.postValue(merged.count { it.status == OrderStatus.CONFIRMED })
        failedCount.postValue(merged.count { it.status in setOf(OrderStatus.FAILED, OrderStatus.AMOUNT_MISMATCH) })
        notFoundCount.postValue(merged.count { it.status == OrderStatus.NOT_FOUND })
    }

    fun setOrders(ordersList: List<OrderItem>) {
        orders.postValue(ordersList)
        pendingCount.postValue(ordersList.count { it.status == OrderStatus.PENDING || it.status == OrderStatus.SCANNING })
    }

    // Pending tasks from heartbeat
    val pendingTasks = MutableLiveData<List<TaskScanner.Task>>(emptyList())

    // Notifications
    val notifications = MutableLiveData<List<DeviceNotification>>(emptyList())
    val unreadNotificationCount = MutableLiveData(0)

    // Helper accessors for diagnostics
    fun getOrders(): List<OrderItem> = orders.value ?: emptyList()
    fun getNotifications(): List<DeviceNotification> = notifications.value ?: emptyList()
    fun getConnectionStatus(): String = when {
        isConnected.value == true && isRegistered.value == true -> "✅ متصل ومسجل"
        isConnected.value == true -> "🟡 متصل لكن غير مسجل"
        else -> "❌ غير متصل: ${connectionMessage.value ?: "—"}"
    }

    // مجموعة لمنع تكرار إشعارات الطلبات
    private val notifiedTaskIds = mutableSetOf<String>()
    // تتبع آخر إشعار اتصال/خطأ لمنع التكرار
    private var lastConnectionNotification: Pair<NotificationType, String>? = null

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
        // منع تكرار إشعار نفس الطلب — فقط إذا كان referenceId جديد
        val refId = notification.referenceId
        if (refId != null && notification.type == NotificationType.ORDER_NEW) {
            if (notifiedTaskIds.contains(refId)) return
            notifiedTaskIds.add(refId)
        }
        // منع إشعارات الاتصال/الحالة المتكررة
        if (notification.type in setOf(NotificationType.CONNECTED, NotificationType.ERROR, NotificationType.SERVER_DOWN, NotificationType.TEST_SUCCESS)) {
            val key = notification.type to notification.message
            if (key == lastConnectionNotification) return
            lastConnectionNotification = key
        }
        val current = notifications.value?.toMutableList() ?: mutableListOf()
        current.add(0, notification)
        // الاحتفاظ بآخر 100 إشعار فقط
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
                OrderEventLogger.matchFound(task.requestId, task.orderNumber, result.message.transactionId)
                OrderEventLogger.orderConfirmed(task.requestId, task.orderNumber, result.message.transactionId)
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
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "not_found: ${result.reason}")
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
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "amount_mismatch: expected=${result.expectedAmount} found=${result.foundAmount}")
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
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "failure: ${result.reason}")
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

    /** تحديث عداد المحاولات والعداد التنازلي في كارت الطلب */
    fun updateOrderScanProgress(requestId: String, attempt: Int, maxAttempts: Int, countdown: Int) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val idx = current.indexOfFirst { it.requestId == requestId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(
                status = OrderStatus.SCANNING,
                scanAttempt = attempt,
                maxAttempts = maxAttempts,
                nextScanCountdown = countdown,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            // لو الطلب لسه مظهرش من الـ heartbeat، نعمل order مؤقت عشان يبين إن الفحص بدأ
            current.add(
                OrderItem(
                    requestId = requestId,
                    orderLabel = "طلب شحن",
                    expectedAmount = 0.0,
                    status = OrderStatus.SCANNING,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    scanAttempt = attempt,
                    maxAttempts = maxAttempts,
                    nextScanCountdown = countdown
                )
            )
        }
        orders.postValue(current)
        pendingCount.postValue(current.count { it.status == OrderStatus.PENDING || it.status == OrderStatus.SCANNING })
    }

    fun addOrUpdateOrder(order: OrderItem) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val idx = current.indexOfFirst { it.requestId == order.requestId }
        if (idx >= 0) {
            // نحافظ على الحالات النهائية فقط (confirmed/failed/not_found/mismatch/duplicate)
            // SCANNING ليست نهائية — نسمح لها بالتغيير دائماً
            val existing = current[idx]
            val isTerminal = existing.status in setOf(
                OrderStatus.CONFIRMED, OrderStatus.FAILED,
                OrderStatus.NOT_FOUND, OrderStatus.AMOUNT_MISMATCH, OrderStatus.DUPLICATE
            )
            current[idx] = if (isTerminal) order.copy(status = existing.status) else order
        } else {
            current.add(0, order)
        }
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
    SCANNING("قيد المراجعة (جاري البحث)", "#3B82F6"),
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
    val customerName: String? = null,
    val paymentMethod: String? = null,
    // مرجع للمهمة للعمليات اليدوية
    val taskId: String? = null,
    // عداد المحاولات
    val scanAttempt: Int = 0,
    val maxAttempts: Int = 3,
    val nextScanCountdown: Int = 0,   // ثواني حتى المحاولة القادمة
    // حقول نظام الطلبات المؤمنة (payment_orders)
    val paymentOrderId: String? = null,
    val orderExpiresAt: String? = null
)
