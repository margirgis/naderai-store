package com.naderai.smsreader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * تخزين الإشعارات محلياً باستخدام SharedPreferences.
 * يضمن ثبات الإشعارات عند إعادة تشغيل التطبيق أو التحديث.
 * يحتفظ بآخر 100 إشعار فقط.
 */
object NotificationStorage {

    private const val PREFS_FILE = "notifications_cache"
    private const val KEY_NOTIFICATIONS = "notifications_json"
    private const val KEY_NOTIFIED_TASK_IDS = "notified_task_ids"
    private const val KEY_NOTIFIED_FINAL_STATUSES = "notified_final_statuses"
    private const val MAX_NOTIFICATIONS = 100

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ── إشعارات ──────────────────────────────────────────────────────────────

    fun saveNotifications(ctx: Context, list: List<DeviceNotification>) {
        try {
            val arr = JSONArray()
            list.takeLast(MAX_NOTIFICATIONS).forEach { n ->
                arr.put(JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("message", n.message)
                    put("type", n.type.name)
                    put("reference_id", n.referenceId ?: JSONObject.NULL)
                    put("timestamp", n.timestamp)
                    put("is_read", n.isRead)
                })
            }
            prefs(ctx).edit().putString(KEY_NOTIFICATIONS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("NotificationStorage", "saveNotifications: ${e.message}")
        }
    }

    fun loadNotifications(ctx: Context): List<DeviceNotification> {
        return try {
            val raw = prefs(ctx).getString(KEY_NOTIFICATIONS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<DeviceNotification>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    DeviceNotification(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        message = obj.optString("message", ""),
                        type = runCatching {
                            NotificationType.valueOf(obj.getString("type"))
                        }.getOrDefault(NotificationType.INFO),
                        referenceId = if (obj.isNull("reference_id")) null else obj.optString("reference_id"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isRead = obj.optBoolean("is_read", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            android.util.Log.e("NotificationStorage", "loadNotifications: ${e.message}")
            emptyList()
        }
    }

    // ── notifiedTaskIds (لمنع تكرار إشعار نفس الطلب) ─────────────────────────

    fun saveNotifiedTaskIds(ctx: Context, ids: Set<String>) {
        try {
            val arr = JSONArray().apply { ids.forEach { put(it) } }
            prefs(ctx).edit().putString(KEY_NOTIFIED_TASK_IDS, arr.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("NotificationStorage", "saveNotifiedTaskIds: ${e.message}")
        }
    }

    fun loadNotifiedTaskIds(ctx: Context): MutableSet<String> {
        return try {
            val raw = prefs(ctx).getString(KEY_NOTIFIED_TASK_IDS, null) ?: return mutableSetOf()
            val arr = JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    // ── notifiedFinalStatuses (لمنع تكرار إشعار الحالة النهائية) ─────────────

    fun saveNotifiedFinalStatuses(ctx: Context, map: Map<String, OrderStatus>) {
        try {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v.name) }
            prefs(ctx).edit().putString(KEY_NOTIFIED_FINAL_STATUSES, obj.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("NotificationStorage", "saveNotifiedFinalStatuses: ${e.message}")
        }
    }

    fun loadNotifiedFinalStatuses(ctx: Context): MutableMap<String, OrderStatus> {
        return try {
            val raw = prefs(ctx).getString(KEY_NOTIFIED_FINAL_STATUSES, null)
                ?: return mutableMapOf()
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, OrderStatus>()
            obj.keys().forEach { k ->
                runCatching { OrderStatus.valueOf(obj.getString(k)) }.getOrNull()
                    ?.let { map[k] = it }
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }
}
