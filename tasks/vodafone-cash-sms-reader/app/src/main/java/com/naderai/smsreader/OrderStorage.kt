package com.naderai.smsreader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * تخزين محلي للطلبات باستخدام SharedPreferences.
 * يحافظ على كل الطلبات (القديمة والجديدة) حتى بعد إغلاق التطبيق أو إعادة التشغيل.
 */
object OrderStorage {

    private const val PREFS_FILE = "orders_cache"
    private const val KEY_ORDERS = "orders_json"
    private const val KEY_VERSION = "orders_cache_version"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * تسجيل إصدار التطبيق بدون مسح أي طلبات.
     * الطلبات تُحضَر من السيرفر عند كل heartbeat — لا يجوز حذفها محلياً.
     */
    fun markVersion(context: Context, versionName: String) {
        getPrefs(context).edit().putString(KEY_VERSION, versionName).apply()
    }

    /**
     * يمسح التخزين المحلي إذا تغيّر إصدار التطبيق.
     * @deprecated استخدم markVersion بدلاً منها — حذف الطلبات عند التحديث خطأ.
     */
    @Deprecated("Never wipe orders on update — use markVersion", ReplaceWith("markVersion(context, versionName)"))
    fun clearIfVersionChanged(context: Context, versionName: String) {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_VERSION, null)
        if (stored != versionName) {
            clear(context)
            prefs.edit().putString(KEY_VERSION, versionName).apply()
            android.util.Log.i("OrderStorage", "Cleared order cache because app version changed from $stored to $versionName")
        }
    }

    fun saveOrders(context: Context, orders: List<OrderItem>) {
        try {
            val json = JSONArray()
            orders.forEach { json.put(orderToJson(it)) }
            getPrefs(context).edit().putString(KEY_ORDERS, json.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("OrderStorage", "Failed to save orders: ${e.message}")
        }
    }

    fun loadOrders(context: Context): List<OrderItem> {
        return try {
            val raw = getPrefs(context).getString(KEY_ORDERS, null) ?: return emptyList()
            val json = JSONArray(raw)
            val orders = mutableListOf<OrderItem>()
            for (i in 0 until json.length()) {
                orders.add(jsonToOrder(json.getJSONObject(i)))
            }
            orders
        } catch (e: Exception) {
            android.util.Log.e("OrderStorage", "Failed to load orders: ${e.message}")
            emptyList()
        }
    }

    fun clear(context: Context) {
        getPrefs(context).edit().remove(KEY_ORDERS).apply()
    }

    private fun orderToJson(order: OrderItem): JSONObject {
        return JSONObject().apply {
            put("request_id", order.requestId)
            put("order_label", order.orderLabel)
            put("expected_amount", order.expectedAmount)
            put("status", order.status.name)
            put("created_at", order.createdAt)
            put("updated_at", order.updatedAt)
            put("failure_reason", order.failureReason ?: JSONObject.NULL)
            put("transaction_id", order.transactionId ?: JSONObject.NULL)
            put("order_number", order.orderNumber ?: JSONObject.NULL)
            put("credits_requested", order.creditsRequested ?: JSONObject.NULL)
            put("customer_email", order.customerEmail ?: JSONObject.NULL)
            put("customer_phone", order.customerPhone ?: JSONObject.NULL)
            put("customer_name", order.customerName ?: JSONObject.NULL)
            put("sender_phone_requested", order.senderPhoneRequested ?: JSONObject.NULL)
            put("sender_name_requested", order.senderNameRequested ?: JSONObject.NULL)
            put("request_created_at", order.requestCreatedAt ?: JSONObject.NULL)
            put("payment_method", order.paymentMethod ?: JSONObject.NULL)
            put("task_id", order.taskId ?: JSONObject.NULL)
            put("scan_attempt", order.scanAttempt)
            put("max_attempts", order.maxAttempts)
            put("next_scan_countdown", order.nextScanCountdown)
            put("payment_order_id", order.paymentOrderId ?: JSONObject.NULL)
            put("order_expires_at", order.orderExpiresAt ?: JSONObject.NULL)
            put("sender_phone_found", order.senderPhoneFound ?: JSONObject.NULL)
            put("sender_name_found", order.senderNameFound ?: JSONObject.NULL)
            put("amount_found", order.amountFound ?: JSONObject.NULL)
            put("receiver_wallet_found", order.receiverWalletFound ?: JSONObject.NULL)
            put("sms_body_found", order.smsBodyFound ?: JSONObject.NULL)
            put("scanned_at", order.scannedAt ?: JSONObject.NULL)
            order.snapshot?.let { put("snapshot", snapshotToJson(it)) }
        }
    }

    private fun snapshotToJson(snapshot: OrderSnapshot): JSONObject {
        return JSONObject().apply {
            put("request_id", snapshot.requestId)
            put("order_label", snapshot.orderLabel)
            put("expected_amount", snapshot.expectedAmount)
            put("created_at", snapshot.createdAt)
            put("order_number", snapshot.orderNumber ?: JSONObject.NULL)
            put("credits_requested", snapshot.creditsRequested ?: JSONObject.NULL)
            put("customer_email", snapshot.customerEmail ?: JSONObject.NULL)
            put("customer_phone", snapshot.customerPhone ?: JSONObject.NULL)
            put("customer_name", snapshot.customerName ?: JSONObject.NULL)
            put("payment_method", snapshot.paymentMethod ?: JSONObject.NULL)
            put("sender_phone_requested", snapshot.senderPhoneRequested ?: JSONObject.NULL)
            put("sender_name_requested", snapshot.senderNameRequested ?: JSONObject.NULL)
            put("request_created_at", snapshot.requestCreatedAt ?: JSONObject.NULL)
            put("payment_order_id", snapshot.paymentOrderId ?: JSONObject.NULL)
            put("order_expires_at", snapshot.orderExpiresAt ?: JSONObject.NULL)
        }
    }

    private fun jsonToOrder(json: JSONObject): OrderItem {
        return OrderItem(
            requestId = json.getString("request_id"),
            orderLabel = json.optString("order_label", "طلب شحن"),
            expectedAmount = json.optDouble("expected_amount", 0.0),
            status = json.takeIfString("status")?.let { OrderStatus.fromString(it) }
                ?: OrderStatus.valueOf(json.getString("status")),
            createdAt = json.getLong("created_at"),
            updatedAt = json.getLong("updated_at"),
            failureReason = json.takeIfString("failure_reason"),
            transactionId = json.takeIfString("transaction_id"),
            orderNumber = json.takeIfLong("order_number"),
            creditsRequested = json.takeIfInt("credits_requested"),
            customerEmail = json.takeIfString("customer_email"),
            customerPhone = json.takeIfString("customer_phone"),
            customerName = json.takeIfString("customer_name"),
            senderPhoneRequested = json.takeIfString("sender_phone_requested"),
            senderNameRequested = json.takeIfString("sender_name_requested"),
            requestCreatedAt = json.takeIfString("request_created_at"),
            paymentMethod = json.takeIfString("payment_method"),
            taskId = json.takeIfString("task_id"),
            scanAttempt = json.optInt("scan_attempt", 0),
            maxAttempts = json.optInt("max_attempts", 3),
            nextScanCountdown = json.optInt("next_scan_countdown", 0),
            paymentOrderId = json.takeIfString("payment_order_id"),
            orderExpiresAt = json.takeIfString("order_expires_at"),
            senderPhoneFound = json.takeIfString("sender_phone_found"),
            senderNameFound = json.takeIfString("sender_name_found"),
            amountFound = json.takeIfDouble("amount_found"),
            receiverWalletFound = json.takeIfString("receiver_wallet_found"),
            smsBodyFound = json.takeIfString("sms_body_found"),
            scannedAt = json.takeIfLong("scanned_at"),
            snapshot = json.takeIfJSONObject("snapshot")?.let { jsonToSnapshot(it) }
        )
    }

    private fun jsonToSnapshot(json: JSONObject): OrderSnapshot {
        return OrderSnapshot(
            requestId = json.getString("request_id"),
            orderLabel = json.optString("order_label", "طلب شحن"),
            expectedAmount = json.optDouble("expected_amount", 0.0),
            createdAt = json.getLong("created_at"),
            orderNumber = json.takeIfLong("order_number"),
            creditsRequested = json.takeIfInt("credits_requested"),
            customerEmail = json.takeIfString("customer_email"),
            customerPhone = json.takeIfString("customer_phone"),
            customerName = json.takeIfString("customer_name"),
            paymentMethod = json.takeIfString("payment_method"),
            senderPhoneRequested = json.takeIfString("sender_phone_requested"),
            senderNameRequested = json.takeIfString("sender_name_requested"),
            requestCreatedAt = json.takeIfString("request_created_at"),
            paymentOrderId = json.takeIfString("payment_order_id"),
            orderExpiresAt = json.takeIfString("order_expires_at")
        )
    }

    private fun JSONObject.takeIfDouble(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key, 0.0).takeIf { it != 0.0 } else null
    }

    private fun JSONObject.takeIfJSONObject(key: String): JSONObject? {
        return if (has(key) && !isNull(key)) optJSONObject(key) else null
    }

    private fun JSONObject.takeIfString(key: String): String? {
        return if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotEmpty() } else null
    }

    private fun JSONObject.takeIfLong(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key, 0L).takeIf { it != 0L } else null
    }

    private fun JSONObject.takeIfInt(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key, 0).takeIf { it != 0 } else null
    }
}
