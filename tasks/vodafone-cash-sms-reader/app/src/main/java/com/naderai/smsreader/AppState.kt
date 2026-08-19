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
     * - لا يحذف الطلبات القديمة.
     * - لا يعيد الحالات النهائية إلى قيد المراجعة.
     * - يحافظ على Snapshot الطلب الأساسي (البيانات لا تظهر وتختفي).
     */
    fun mergeOrders(newOrders: List<OrderItem>) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val currentMap = current.associateBy { it.requestId }.toMutableMap()

        for (order in newOrders) {
            val existing = currentMap[order.requestId]
            currentMap[order.requestId] = if (existing != null) {
                // الحالة النهائية من السيرفر أو المحلية تُغلق الطلب
                val mergedStatus = when {
                    order.status.isTerminal() -> order.status
                    existing.status.isTerminal() -> existing.status
                    order.status == OrderStatus.PENDING && existing.status == OrderStatus.SCANNING -> existing.status
                    order.status == OrderStatus.PENDING -> OrderStatus.PENDING
                    else -> existing.status
                }
                existing.withSnapshotPreserved(order).copy(
                    status = mergedStatus,
                    updatedAt = order.updatedAt,
                    // نحافظ على عداد المحاولات المحلي أثناء الفحص
                    scanAttempt = if (existing.status == OrderStatus.SCANNING) existing.scanAttempt else order.scanAttempt,
                    maxAttempts = maxOf(existing.maxAttempts, order.maxAttempts),
                    nextScanCountdown = if (existing.status == OrderStatus.SCANNING) existing.nextScanCountdown else order.nextScanCountdown,
                    failureReason = order.failureReason ?: existing.failureReason,
                    transactionId = order.transactionId ?: existing.transactionId,
                    scanStatus = order.scanStatus ?: existing.scanStatus,
                    resultStatus = order.resultStatus ?: existing.resultStatus
                )
            } else {
                // أول مرة نرى الطلب — نحفظ snapshot تلقائياً
                val snap = order.resolvedSnapshot()
                order.copy(snapshot = snap)
            }
        }

        val merged = currentMap.values.sortedByDescending { it.createdAt }
        orders.postValue(merged)
        refreshCounts(merged)
    }

    private fun refreshCounts(merged: List<OrderItem>) {
        pendingCount.postValue(merged.count { it.status in setOf(OrderStatus.PENDING, OrderStatus.SCANNING, OrderStatus.MATCHING, OrderStatus.WAITING_CONFIRMATION) })
        confirmedCount.postValue(merged.count { it.status in setOf(OrderStatus.CONFIRMED, OrderStatus.COMPLETED, OrderStatus.MATCHED) })
        failedCount.postValue(merged.count { it.status in setOf(OrderStatus.FAILED, OrderStatus.AMOUNT_MISMATCH, OrderStatus.NOT_FOUND) })
        notFoundCount.postValue(merged.count { it.status == OrderStatus.NOT_FOUND })
    }

    fun setOrders(ordersList: List<OrderItem>) {
        orders.postValue(ordersList)
        refreshCounts(ordersList)
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

    private val notifiedFinalStatuses = mutableMapOf<String, OrderStatus>()

    fun onTaskResult(task: TaskScanner.Task, result: TaskScanner.ScanResult) {
        lastSmsScannedAt.postValue(System.currentTimeMillis())
        when (result) {
            is TaskScanner.ScanResult.Success -> {
                lastFoundTransaction.postValue(result.message.transactionId)
                updateOrderAfterScan(task.requestId) {
                    it.copy(
                        status = OrderStatus.COMPLETED,
                        transactionId = result.message.transactionId ?: it.transactionId,
                        amountFound = result.message.amount ?: it.amountFound,
                        senderPhoneFound = result.message.senderPhone ?: it.senderPhoneFound,
                        senderNameFound = result.message.senderName ?: it.senderNameFound,
                        receiverWalletFound = result.message.receiverWallet ?: it.receiverWalletFound,
                        smsBodyFound = result.message.body,
                        scannedAt = result.message.date,
                        failureReason = null
                    )
                }
                OrderEventLogger.matchFound(task.requestId, task.orderNumber, result.message.transactionId)
                OrderEventLogger.orderConfirmed(task.requestId, task.orderNumber, result.message.transactionId)
                notifyOnce(task.requestId, OrderStatus.COMPLETED) {
                    DeviceNotification(
                        title = "تم التأكيد والإكمال ✓",
                        message = "المبلغ: ${result.message.amount} — رقم العملية: ${result.message.transactionId ?: "—"}",
                        type = NotificationType.ORDER_CONFIRMED,
                        referenceId = task.requestId
                    )
                }
            }
            is TaskScanner.ScanResult.NotFound -> {
                updateOrderAfterScan(task.requestId) {
                    it.copy(
                        status = OrderStatus.NOT_FOUND,
                        failureReason = result.reason
                    )
                }
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "not_found: ${result.reason}")
                notifyOnce(task.requestId, OrderStatus.NOT_FOUND) {
                    DeviceNotification(
                        title = "لم يتم العثور على العملية",
                        message = result.reason,
                        type = NotificationType.ORDER_NOT_FOUND,
                        referenceId = task.requestId
                    )
                }
            }
            is TaskScanner.ScanResult.AmountMismatch -> {
                updateOrderAfterScan(task.requestId) {
                    it.copy(
                        status = OrderStatus.AMOUNT_MISMATCH,
                        amountFound = result.foundAmount,
                        failureReason = "مبلغ غير مطابق: المطلوب ${result.expectedAmount} — الموجود ${result.foundAmount}"
                    )
                }
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "amount_mismatch: expected=${result.expectedAmount} found=${result.foundAmount}")
                notifyOnce(task.requestId, OrderStatus.AMOUNT_MISMATCH) {
                    DeviceNotification(
                        title = "مبلغ غير مطابق",
                        message = "المطلوب: ${result.expectedAmount} — الموجود: ${result.foundAmount}",
                        type = NotificationType.ORDER_MISMATCH,
                        referenceId = task.requestId
                    )
                }
            }
            is TaskScanner.ScanResult.Failure -> {
                updateOrderAfterScan(task.requestId) {
                    it.copy(
                        status = OrderStatus.FAILED,
                        failureReason = result.reason
                    )
                }
                OrderEventLogger.orderRejected(task.requestId, task.orderNumber, "failure: ${result.reason}")
                notifyOnce(task.requestId, OrderStatus.FAILED) {
                    DeviceNotification(
                        title = "فشل الفحص",
                        message = result.reason,
                        type = NotificationType.ERROR,
                        referenceId = task.requestId
                    )
                }
            }
        }
    }

    /** يحدث الطلب ويحافظ على Snapshot ويمنع مسح البيانات الأساسية */
    private fun updateOrderAfterScan(requestId: String, transform: (OrderItem) -> OrderItem) {
        val current = orders.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.requestId == requestId }
        if (idx >= 0) {
            val existing = current[idx]
            current[idx] = existing.withSnapshotPreserved(transform(existing).copy(updatedAt = System.currentTimeMillis()))
            orders.postValue(current)
            refreshCounts(current)
        }
    }

    private fun notifyOnce(requestId: String, status: OrderStatus, build: () -> DeviceNotification) {
        if (notifiedFinalStatuses[requestId] == status) return
        notifiedFinalStatuses[requestId] = status
        addNotification(build())
    }

    fun updateOrderStatus(requestId: String, status: OrderStatus) {
        val current = orders.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.requestId == requestId }
        if (idx >= 0) {
            current[idx] = current[idx].withSnapshotPreserved(
                current[idx].copy(status = status, updatedAt = System.currentTimeMillis())
            )
            orders.postValue(current)
            refreshCounts(current)
        }
    }

    /** تحديث عداد المحاولات والعداد التنازلي في كارت الطلب */
    fun updateOrderScanProgress(requestId: String, attempt: Int, maxAttempts: Int, countdown: Int) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val idx = current.indexOfFirst { it.requestId == requestId }
        if (idx >= 0) {
            current[idx] = current[idx].withSnapshotPreserved(
                current[idx].copy(
                    status = OrderStatus.SCANNING,
                    scanAttempt = attempt,
                    maxAttempts = maxAttempts,
                    nextScanCountdown = countdown,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            // لو الطلب لسه مظهرش من الـ heartbeat، نعمل order مؤقت عشان يبين إن الفحص بدأ
            val placeholder = OrderItem(
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
            current.add(placeholder.withSnapshotPreserved(placeholder))
        }
        orders.postValue(current)
        refreshCounts(current)
    }

    fun addOrUpdateOrder(order: OrderItem) {
        val current = orders.value?.toMutableList() ?: mutableListOf()
        val idx = current.indexOfFirst { it.requestId == order.requestId }
        if (idx >= 0) {
            val existing = current[idx]
            val newStatus = when {
                order.status.isTerminal() -> order.status
                existing.status.isTerminal() -> existing.status
                else -> order.status
            }
            current[idx] = existing.withSnapshotPreserved(order).copy(status = newStatus)
        } else {
            // أول وصول — نحفظ Snapshot فوراً
            current.add(0, order.copy(snapshot = order.resolvedSnapshot()))
        }
        orders.postValue(current)
        refreshCounts(current)
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
    NEW("جديد", "#64748B"),
    RECEIVED("تم استلام الطلب", "#8B5CF6"),
    PENDING("قيد الانتظار", "#F59E0B"),
    SCANNING("جاري الفحص", "#3B82F6"),
    MATCHING("جاري المطابقة", "#0EA5E9"),
    MATCHED("تم العثور على تطابق", "#10B981"),
    WAITING_CONFIRMATION("بانتظار التأكيد", "#F97316"),
    CONFIRMED("تم التأكيد", "#059669"),
    COMPLETED("مكتمل", "#047857"),
    AMOUNT_MISMATCH("مبلغ غير مطابق", "#EF4444"),
    MANUAL_REVIEW("مراجعة يدوية", "#F97316"),
    NOT_FOUND("لم يتم العثور", "#6B7280"),
    FAILED("فشل", "#DC2626"),
    DUPLICATE("مكرر", "#8B5CF6"),
    EXPIRED("انتهت الصلاحية", "#64748B"),
    ADMIN_OFFLINE("الجهاز غير متصل", "#F97316"),
    WAITING_FOR_VERIFICATION("ينتظر الجهاز", "#FBBF24"),
    REOPENED("أُعيد فتحه", "#8B5CF6");

    /** الحالات النهائية التي لا يُعاد فتحها بعدها */
    fun isTerminal(): Boolean = this in setOf(
        COMPLETED, CONFIRMED, FAILED, NOT_FOUND, AMOUNT_MISMATCH, DUPLICATE, EXPIRED
    )

    companion object {
        fun fromString(s: String?): OrderStatus = when (s?.lowercase()) {
            "new" -> NEW
            "received" -> RECEIVED
            "pending" -> PENDING
            "scanning" -> SCANNING
            "matching" -> MATCHING
            "matched" -> MATCHED
            "waiting_confirmation", "waiting-confirmation" -> WAITING_CONFIRMATION
            "found", "verified" -> MATCHED
            "confirmed", "approved" -> COMPLETED
            "completed" -> COMPLETED
            "amount_mismatch", "amount-mismatch" -> AMOUNT_MISMATCH
            "manual_review", "manual-review" -> MANUAL_REVIEW
            "not_found", "not-found" -> NOT_FOUND
            "failed", "rejected" -> FAILED
            "expired" -> EXPIRED
            "reopened" -> REOPENED
            "admin_offline", "admin-offline" -> ADMIN_OFFLINE
            "waiting_for_verification", "waiting-for-verification" -> WAITING_FOR_VERIFICATION
            "duplicate" -> DUPLICATE
            else -> PENDING
        }
    }
}

/**
 * Snapshot ثابت للطلب — يُحفظ عند أول وصول للطلب ولا يُتغيّر أثناء الفحص.
 * يحمي من ظاهرة "البيانات تظهر وتختفي" عند تحديثات السيرفر.
 */
data class OrderSnapshot(
    val requestId: String,
    val orderLabel: String,
    val expectedAmount: Double,
    val createdAt: Long,
    val orderNumber: Long? = null,
    val creditsRequested: Int? = null,
    val customerEmail: String? = null,
    val customerPhone: String? = null,
    val customerName: String? = null,
    val paymentMethod: String? = null,
    val senderPhoneRequested: String? = null,
    val senderNameRequested: String? = null,
    val requestCreatedAt: String? = null,
    val paymentOrderId: String? = null,
    val orderExpiresAt: String? = null
)

data class OrderItem(
    val requestId: String,
    val orderLabel: String,
    val expectedAmount: Double,
    val status: OrderStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val failureReason: String? = null,
    val transactionId: String? = null,
    // تفاصيل كاملة من الموقع (Snapshot)
    val orderNumber: Long? = null,
    val creditsRequested: Int? = null,
    val customerEmail: String? = null,
    val customerPhone: String? = null,
    // اسم صاحب الحساب الحقيقي (من profiles.full_name) — ليس اسم المُحوِّل من SMS
    val customerName: String? = null,
    val paymentMethod: String? = null,
    // رقم محفظة المُحوِّل (المرسل) — مختلف عن customerPhone الذي هو رقم صاحب الحساب
    val senderPhoneRequested: String? = null,
    val senderNameRequested: String? = null,
    // وقت إنشاء الطلب من السيرفر
    val requestCreatedAt: String? = null,
    // مرجع للمهمة للعمليات اليدوية
    val taskId: String? = null,
    // عداد المحاولات
    val scanAttempt: Int = 0,
    val maxAttempts: Int = 3,
    val nextScanCountdown: Int = 0,   // ثواني حتى المحاولة القادمة
    // حقول نظام الطلبات المؤمنة (payment_orders)
    val paymentOrderId: String? = null,
    val orderExpiresAt: String? = null,
    // حالات إضافية من السيرفر
    val scanStatus: String? = null,
    val resultStatus: String? = null,
    // بيانات فحص الرسالة (ديناميكية — تُحدّث أثناء المطابقة)
    val senderPhoneFound: String? = null,
    val senderNameFound: String? = null,
    val amountFound: Double? = null,
    val receiverWalletFound: String? = null,
    val smsBodyFound: String? = null,
    val scannedAt: Long? = null,
    // Snapshot ثابت للطلب
    val snapshot: OrderSnapshot? = null
) {
    /** تُرجع الـ Snapshot إذا وُجد، وإلا تُنشئ snapshot من الحقول الحالية */
    fun resolvedSnapshot(): OrderSnapshot = snapshot ?: OrderSnapshot(
        requestId = requestId,
        orderLabel = orderLabel,
        expectedAmount = expectedAmount,
        createdAt = createdAt,
        orderNumber = orderNumber,
        creditsRequested = creditsRequested,
        customerEmail = customerEmail,
        customerPhone = customerPhone,
        customerName = customerName,
        paymentMethod = paymentMethod,
        senderPhoneRequested = senderPhoneRequested,
        senderNameRequested = senderNameRequested,
        requestCreatedAt = requestCreatedAt,
        paymentOrderId = paymentOrderId,
        orderExpiresAt = orderExpiresAt
    )

    /** الحقول الأساسية التي لا يجوز أن تُمسح بـ null من السيرفر */
    fun withSnapshotPreserved(other: OrderItem): OrderItem {
        val snap = this.snapshot ?: this.resolvedSnapshot()
        return other.copy(
            orderLabel = snap.orderLabel,
            expectedAmount = snap.expectedAmount,
            orderNumber = other.orderNumber ?: snap.orderNumber,
            creditsRequested = other.creditsRequested ?: snap.creditsRequested,
            customerEmail = other.customerEmail ?: snap.customerEmail,
            customerPhone = other.customerPhone ?: snap.customerPhone,
            customerName = other.customerName ?: snap.customerName,
            paymentMethod = other.paymentMethod ?: snap.paymentMethod,
            senderPhoneRequested = other.senderPhoneRequested ?: snap.senderPhoneRequested,
            senderNameRequested = other.senderNameRequested ?: snap.senderNameRequested,
            requestCreatedAt = other.requestCreatedAt ?: snap.requestCreatedAt,
            paymentOrderId = other.paymentOrderId ?: snap.paymentOrderId,
            orderExpiresAt = other.orderExpiresAt ?: snap.orderExpiresAt,
            snapshot = snap,
            // الحقول الديناميكية من الحالة الحالية لا تُمسح
            senderPhoneFound = other.senderPhoneFound ?: this.senderPhoneFound,
            senderNameFound = other.senderNameFound ?: this.senderNameFound,
            amountFound = other.amountFound ?: this.amountFound,
            receiverWalletFound = other.receiverWalletFound ?: this.receiverWalletFound,
            smsBodyFound = other.smsBodyFound ?: this.smsBodyFound,
            scannedAt = other.scannedAt ?: this.scannedAt
        )
    }

    fun isExpired(): Boolean {
        if (orderExpiresAt.isNullOrEmpty()) return false
        return try {
            java.time.Instant.parse(orderExpiresAt).isBefore(java.time.Instant.now())
        } catch (e: Exception) {
            false
        }
    }
}
