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

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

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
            put("payment_method", order.paymentMethod ?: JSONObject.NULL)
            put("task_id", order.taskId ?: JSONObject.NULL)
            put("scan_attempt", order.scanAttempt)
            put("max_attempts", order.maxAttempts)
            put("next_scan_countdown", order.nextScanCountdown)
            put("payment_order_id", order.paymentOrderId ?: JSONObject.NULL)
            put("order_expires_at", order.orderExpiresAt ?: JSONObject.NULL)
        }
    }

    private fun jsonToOrder(json: JSONObject): OrderItem {
        return OrderItem(
            requestId = json.getString("request_id"),
            orderLabel = json.optString("order_label", "طلب شحن"),
            expectedAmount = json.optDouble("expected_amount", 0.0),
            status = OrderStatus.valueOf(json.getString("status")),
            createdAt = json.getLong("created_at"),
            updatedAt = json.getLong("updated_at"),
            failureReason = json.takeIfString("failure_reason"),
            transactionId = json.takeIfString("transaction_id"),
            orderNumber = json.takeIfLong("order_number"),
            creditsRequested = json.takeIfInt("credits_requested"),
            customerEmail = json.takeIfString("customer_email"),
            customerPhone = json.takeIfString("customer_phone"),
            paymentMethod = json.takeIfString("payment_method"),
            taskId = json.takeIfString("task_id"),
            scanAttempt = json.optInt("scan_attempt", 0),
            maxAttempts = json.optInt("max_attempts", 3),
            nextScanCountdown = json.optInt("next_scan_countdown", 0),
            paymentOrderId = json.takeIfString("payment_order_id"),
            orderExpiresAt = json.takeIfString("order_expires_at")
        )
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
