package com.naderai.smsreader

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * يزامن كل الطلبات (القديمة والجديدة) من السيرفر للأدمن.
 * يعمل بشكل مستقل عن HeartbeatManager ويمكن تشغيله دورياً أو عند حدث.
 */
class OrderSyncManager(
    private val context: Context,
    private val adminUrl: String,
    private val onResult: (success: Boolean, message: String) -> Unit = { _, _ -> }
) {
    private val handler = Handler(Looper.getMainLooper())
    private val deviceId: String get() = HeartbeatManager.getDeviceId(context)
    private val syncRunnable = object : Runnable {
        override fun run() {
            sync()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 60_000L
        private const val TAG = "OrderSyncManager"

        /**
         * يبني كائن OrderItem من الاستجابة الكاملة للسيرفر.
         */
        fun parseOrder(obj: JSONObject): OrderItem {
            val requestId = obj.getString("request_id")
            val orderNumber = if (obj.has("payment_order_number") && !obj.isNull("payment_order_number")) {
                obj.getLong("payment_order_number")
            } else if (obj.has("order_number") && !obj.isNull("order_number")) {
                obj.getLong("order_number")
            } else null

            val status = OrderStatus.fromString(obj.optString("status"))
            val scanStatus = obj.optString("scan_status")
            val effectiveStatus = when {
                status == OrderStatus.PENDING && scanStatus == "scanning" -> OrderStatus.SCANNING
                else -> status
            }

            val createdAt = try {
                java.time.Instant.parse(obj.optString("created_at", "1970-01-01T00:00:00Z")).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
            val updatedAt = try {
                java.time.Instant.parse(obj.optString("updated_at", "1970-01-01T00:00:00Z")).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            return OrderItem(
                requestId = requestId,
                orderLabel = "طلب #${orderNumber ?: requestId.take(8)}",
                expectedAmount = obj.optDouble("amount_requested", 0.0),
                status = effectiveStatus,
                createdAt = createdAt,
                updatedAt = updatedAt,
                failureReason = obj.optString("failure_reason").takeIf { it.isNotEmpty() },
                transactionId = obj.optString("transaction_id").takeIf { it.isNotEmpty() },
                orderNumber = orderNumber,
                creditsRequested = obj.optInt("credits_requested", 0).takeIf { it > 0 },
                customerEmail = obj.optString("customer_email").takeIf { it.isNotEmpty() },
                customerPhone = obj.optString("customer_phone").takeIf { it.isNotEmpty() },
                paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                taskId = obj.optString("task_id").takeIf { it.isNotEmpty() },
                paymentOrderId = obj.optString("payment_order_id").takeIf { it.isNotEmpty() },
                orderExpiresAt = obj.optString("order_expires_at").takeIf { it.isNotEmpty() }
            )
        }

        /**
         * يبني كائن TaskScanner.Task من عنصر pending_tasks.
         */
        fun parseTask(obj: JSONObject): TaskScanner.Task {
            return TaskScanner.Task(
                taskId = obj.getString("task_id"),
                requestId = obj.getString("request_id"),
                amountRequested = obj.optDouble("amount_requested", 0.0),
                senderPhoneRequested = obj.optString("sender_phone_requested").takeIf { it.isNotEmpty() },
                senderNameRequested = obj.optString("sender_name_requested").takeIf { it.isNotEmpty() },
                fingerprintAmount = if (obj.has("fingerprint_amount")) obj.optDouble("fingerprint_amount") else null,
                creditsAmount = if (obj.has("credits_amount")) obj.optDouble("credits_amount") else null,
                orderNumber = if (obj.has("order_number") && !obj.isNull("order_number")) obj.getLong("order_number") else null,
                creditsRequested = if (obj.has("credits_requested") && !obj.isNull("credits_requested")) obj.getInt("credits_requested") else null,
                customerEmail = obj.optString("customer_email").takeIf { it.isNotEmpty() },
                customerPhone = obj.optString("customer_phone").takeIf { it.isNotEmpty() },
                paymentMethod = obj.optString("payment_method").takeIf { it.isNotEmpty() },
                requestCreatedAt = obj.optString("request_created_at").takeIf { it.isNotEmpty() },
                paymentOrderId = obj.optString("payment_order_id").takeIf { it.isNotEmpty() },
                orderExpiresAt = obj.optString("order_expires_at").takeIf { it.isNotEmpty() }
            )
        }
    }

    fun start() {
        if (AdminSession.isLoggedIn(context)) {
            handler.removeCallbacks(syncRunnable)
            sync()
            handler.postDelayed(syncRunnable, SYNC_INTERVAL_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(syncRunnable)
    }

    fun sync() {
        if (!AdminSession.isLoggedIn(context)) {
            onResult(false, "لم يتم تسجيل الدخول كأدمن")
            return
        }

        val accessToken = AdminSession.accessToken(context) ?: return
        val refreshToken = AdminSession.refreshToken(context)

        val body = mapOf(
            "device_id" to deviceId,
            "access_token" to accessToken,
            "refresh_token" to (refreshToken ?: "")
        )

        WebhookSender.sendAdminJson(adminUrl, body) { success, message, responseBody ->
            if (success) {
                parseResponse(responseBody)
            }
            onResult(success, message)
        }
    }

    private fun parseResponse(responseBody: String) {
        try {
            val json = JSONObject(responseBody)

            // تحديث التوكنات إذا تم تجديدها
            val tokens = json.optJSONObject("tokens")
            if (tokens != null) {
                AdminSession.updateTokens(
                    context,
                    tokens.optString("access_token").takeIf { it.isNotEmpty() },
                    tokens.optString("refresh_token").takeIf { it.isNotEmpty() },
                    if (tokens.has("expires_at") && !tokens.isNull("expires_at")) tokens.getLong("expires_at") else null
                )
            }

            // تحليل كل الطلبات
            val orders = mutableListOf<OrderItem>()
            if (json.has("all_orders")) {
                val arr = json.getJSONArray("all_orders")
                for (i in 0 until arr.length()) {
                    orders.add(parseOrder(arr.getJSONObject(i)))
                }
            }

            // تحليل المهام المعلقة للجهاز
            val pendingTasks = mutableListOf<TaskScanner.Task>()
            if (json.has("pending_tasks")) {
                val arr = json.getJSONArray("pending_tasks")
                for (i in 0 until arr.length()) {
                    pendingTasks.add(parseTask(arr.getJSONObject(i)))
                }
            }

            // دمج الطلبات مع التخزين المحلي
            AppState.mergeOrders(orders)
            OrderStorage.saveOrders(context, AppState.getOrders())

            // إطلاق فحص SMS للمهام المعلقة
            if (pendingTasks.isNotEmpty()) {
                AppState.pendingTasks.postValue(pendingTasks)
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val rawUrl = prefs.getString(MainActivity.KEY_WEBHOOK_URL, null)
                val webhookUrl = SupabaseConfig.getWebhookUrl(rawUrl)
                val secret = prefs.getString(MainActivity.KEY_SECRET, null)
                if (!webhookUrl.isNullOrEmpty() && !secret.isNullOrEmpty()) {
                    SmsMonitorService.handlePendingTasks(context, pendingTasks, webhookUrl, secret)
                }
            }

            // معالجة الأوامر من السيرفر
            if (json.has("commands")) {
                val cmds = json.getJSONArray("commands")
                for (i in 0 until cmds.length()) {
                    // الأوامر لا تُستخدم حالياً في وضع الأدمن
                    android.util.Log.d(TAG, "Server command: ${cmds.getJSONObject(i)}")
                }
            }

            AppState.updateFromHeartbeat(true, "تمت مزامنة ${orders.size} طلب")
            AppState.lastSyncTime.postValue(System.currentTimeMillis())
            android.util.Log.d(TAG, "Synced ${orders.size} orders, ${pendingTasks.size} pending tasks")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to parse admin orders response: ${e.message}")
        }
    }
}
