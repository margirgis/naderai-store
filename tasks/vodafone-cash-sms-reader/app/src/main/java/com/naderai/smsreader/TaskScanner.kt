package com.naderai.smsreader

import android.content.Context
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Scans device SMS inbox for a task's matching Vodafone Cash message.
 */
object TaskScanner {

    private const val TAG = "TaskScanner"
    const val MAX_SCAN_ATTEMPTS = 3
    private const val SCAN_INTERVAL_MS = 20_000L

    private val KEYWORDS = listOf(
        "فودافون كاش", "vodafone cash", "استلمت", "لقد استلمت",
        "تم استلام", "received egp", "you have received", "تحويل", "مبلغ"
    )

    // الشروط اللازمة لاعتبار الرسالة رسالة فودافون كاش رسمية
    // الرسالة لازم تحتوي على مؤشر فودافون + إنها رسالة "استلام" (مش "تحويل" صادر)
    private val MANDATORY_KEYWORDS = listOf(
        "فودافون",
        "vodafone",
        "vfcash"
    )
    private val RECEIVED_KEYWORDS = listOf(
        "تم استلام",
        "استلام",
        "استلمت",
        "لقد استلمت"
    )
    private val OUTGOING_KEYWORDS = listOf(
        "تم تحويل",
        "تحويل",
        "تم سحب"
    )

    data class Task(
        val taskId: String,
        val requestId: String,
        val amountRequested: Double,
        val senderPhoneRequested: String?,
        val senderNameRequested: String?,
        val fingerprintAmount: Double?,
        val creditsAmount: Double?,
        // بيانات كاملة من الموقع
        val orderNumber: Long?,
        val creditsRequested: Int?,
        val customerEmail: String?,
        val customerPhone: String?,
        val paymentMethod: String?,
        val requestCreatedAt: String?,
        // بيانات payment_order الجديدة
        val paymentOrderId: String?,
        val orderExpiresAt: String?
    )

    data class ParsedSms(
        val senderPhone: String?,
        val senderName: String?,
        val amount: Double?,
        val transactionId: String?,
        val body: String,
        val date: Long,
        val receiverWallet: String? = null
    )

    /**
     * يفحص صندوق الرسائل كل 20 ثانية — حد أقصى 3 مرات (دقيقة واحدة).
     * لو لقى تطابق يبعت النتيجة فوراً. لو انتهت المحاولات يبعت آخر نتيجة.
     * بيبعث تحديث للـ UI بعدد المحاولة والعداد التنازلي.
     */
    fun scanAndReport(
        context: Context,
        task: Task,
        webhookUrl: String,
        secret: String,
        onResult: ((ScanResult, Boolean) -> Unit)? = null
    ) {
        val handler = CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Scan crashed for task ${task.taskId}: ${e.message}", e)
            AppState.updateOrderStatus(task.requestId, OrderStatus.FAILED)
            AppState.addNotification(DeviceNotification(
                title = "تعطّل الفحص",
                message = "خطأ داخلي: ${e.message}",
                type = NotificationType.ERROR,
                referenceId = task.requestId
            ))
            sendTaskResult(context, task, ScanResult.Failure("خطأ داخلي: ${e.message}"), webhookUrl, secret) { _ ->
                onResult?.invoke(ScanResult.Failure("خطأ داخلي: ${e.message}"), false)
            }
        }

        CoroutineScope(Dispatchers.IO + SupervisorJob() + handler).launch {
            var attempt = 0
            var lastResult: ScanResult = ScanResult.NotFound("لم يبدأ الفحص")
            try {
                while (attempt < MAX_SCAN_ATTEMPTS) {
                    attempt++
                    // تحديث UI: المحاولة الحالية
                    AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, 0)
                    Log.d(TAG, "Scan attempt $attempt/${MAX_SCAN_ATTEMPTS} for task ${task.taskId}")
                    lastResult = scanInbox(context, task)
                    if (lastResult is ScanResult.Success) break

                    if (attempt < MAX_SCAN_ATTEMPTS) {
                        // عداد تنازلي 20 ثانية مع تحديث UI كل ثانية
                        var remaining = (SCAN_INTERVAL_MS / 1000).toInt()
                        while (remaining > 0) {
                            AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, remaining)
                            kotlinx.coroutines.delay(1_000L)
                            remaining--
                        }
                    }
                }
                // إرسال النتيجة النهائية مرة واحدة فقط
                AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, 0)
                sendTaskResult(context, task, lastResult, webhookUrl, secret) { success ->
                    onResult?.invoke(lastResult, success)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan exception for task ${task.taskId}: ${e.message}", e)
                throw e
            }
        }
    }

    private fun scanInbox(context: Context, task: Task): ScanResult {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return ScanResult.Failure("إذن قراءة الرسائل غير ممنوح")
        }

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            null,
            null,
            Telephony.Sms.DATE + " DESC"
        ) ?: return ScanResult.Failure("لا يمكن قراءة صندوق الرسائل")

        val candidates = mutableListOf<ScannedMessage>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "unknown"
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))

                if (isOfficialVodafoneCashMessage(body)) {
                    val parsed = parseSmsBody(body)
                    val normalized = normalizeEgyptianPhone(parsed.senderPhone ?: sender)
                    val requested = normalizeEgyptianPhone(task.senderPhoneRequested ?: "")
                    val amount = parsed.amount

                    // المبلغ المطلوب: fingerprintAmount لو موجود، وإلا amountRequested
                    val targetAmount = task.fingerprintAmount ?: task.amountRequested
                    val amountMatch = if (targetAmount > 0 && amount != null) {
                        kotlin.math.abs(targetAmount - amount) <= 0.01
                    } else false

                    val phoneMatch = requested.isNotEmpty() && normalized == requested

                    // مطابقة الاسم كتأكيد إضافي (اختياري، لا تمنع التأكيد لو الـ SMS بالعكس)
                    val nameMatch = isNameMatch(task.senderNameRequested, parsed.senderName)

                    candidates.add(ScannedMessage(
                        sender = sender,
                        senderPhone = normalized,
                        senderName = parsed.senderName,
                        amount = amount,
                        transactionId = parsed.transactionId,
                        body = body,
                        date = date,
                        amountMatch = amountMatch,
                        phoneMatch = phoneMatch,
                        score = (if (amountMatch) 2 else 0) +
                                (if (phoneMatch) 2 else 0) +
                                (if (nameMatch) 1 else 0),
                        receiverWallet = parsed.receiverWallet
                    ))
                }
            }
        }

        // Best match: score 4 = both amount and phone match
        val best = candidates.maxByOrNull { it.score }
        if (best != null && best.amountMatch && best.phoneMatch) {
            return ScanResult.Success(best)
        }

        // Amount mismatch: found a message from same sender but amount differs
        val phoneOnlyMatch = candidates.filter { it.phoneMatch && !it.amountMatch }
        if (phoneOnlyMatch.isNotEmpty()) {
            val closest = phoneOnlyMatch.maxByOrNull { it.score }!!
            return ScanResult.AmountMismatch(
                closest,
                expectedAmount = task.fingerprintAmount ?: task.amountRequested,
                foundAmount = closest.amount ?: 0.0
            )
        }

        if (candidates.isEmpty()) {
            return ScanResult.NotFound("لم يتم العثور على رسائل فودافون كاش في الجهاز")
        }

        return ScanResult.NotFound("تم العثور على ${candidates.size} رسالة لكن لا توجد مطابقة تامة")
    }

    fun sendTaskResult(
        context: Context,
        task: Task,
        result: ScanResult,
        webhookUrl: String,
        secret: String,
        onSent: ((Boolean) -> Unit)? = null
    ) {
        val idempotencyKey = "${task.taskId}-${task.requestId}"
        val resultStatus = when (result) {
            is ScanResult.Success -> "success"
            is ScanResult.AmountMismatch -> "amount_mismatch"
            is ScanResult.NotFound -> "not_found"
            is ScanResult.Failure -> "failure"
        }

        // إذا كان هناك جلسة أدمن، نرسل النتيجة عبر endpoint الأدمن بدون Webhook Secret
        if (AdminSession.isLoggedIn(context)) {
            val adminUrl = SupabaseConfig.getAdminTaskResultUrl(
                context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_WEBHOOK_URL, null)
            )
            if (!adminUrl.isNullOrEmpty()) {
                sendAdminTaskResult(context, adminUrl, task, result, idempotencyKey, resultStatus) { success ->
                    onSent?.invoke(success)
                }
                return
            }
        }

        if (webhookUrl.isEmpty() || secret.isEmpty()) {
            Log.w(TAG, "No webhook or admin config available; cannot send task result")
            taskResultCallback?.invoke(task, result)
            onSent?.invoke(false)
            return
        }

        val body = mutableMapOf<String, Any>(
            "action" to "task_result",
            "device_id" to HeartbeatManager.getDeviceId(context),
            "task_id" to task.taskId,
            "idempotency_key" to idempotencyKey,
            "status" to resultStatus
        )

        if (result is ScanResult.Success) {
            val m = result.message
            val smsDateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(m.date))
            body["result_data"] = mapOf(
                "sender_phone"     to (m.senderPhone ?: ""),
                "sender_name"      to (m.senderName ?: ""),
                "amount"           to (m.amount ?: 0.0),
                "transaction_id"   to (m.transactionId ?: ""),
                "transaction_time" to smsDateIso,
                "receiver_wallet"  to (m.receiverWallet ?: ""),
                "sms_body"         to m.body,
                "scanned_at"       to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
        }

        if (result is ScanResult.Failure)      body["failure_reason"] = result.reason
        if (result is ScanResult.NotFound)     body["failure_reason"] = result.reason
        if (result is ScanResult.AmountMismatch) body["failure_reason"] = "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"

        // إضافة معرّف طلب الدفع + وقت انتهاء الصلاحية لـ Edge Function لتنفيذ confirm_payment_order
        if (!task.paymentOrderId.isNullOrEmpty()) body["payment_order_id"] = task.paymentOrderId
        if (!task.orderExpiresAt.isNullOrEmpty())  body["order_expires_at"] = task.orderExpiresAt

        WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { success, msg, _ ->
            Log.d(TAG, "Task result sent: $success — $msg")
            // Notify local observer for UI update
            taskResultCallback?.invoke(task, result)
            onSent?.invoke(success)
        }
    }

    private fun sendAdminTaskResult(
        context: Context,
        adminUrl: String,
        task: Task,
        result: ScanResult,
        idempotencyKey: String,
        resultStatus: String,
        onSent: ((Boolean) -> Unit)? = null
    ) {
        val accessToken = AdminSession.accessToken(context) ?: return
        val refreshToken = AdminSession.refreshToken(context)

        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val resultData = when (result) {
            is ScanResult.Success -> {
                val m = result.message
                mapOf(
                    "sender_phone"     to (m.senderPhone ?: ""),
                    "sender_name"      to (m.senderName ?: ""),
                    "amount"           to (m.amount ?: 0.0),
                    "transaction_id"   to (m.transactionId ?: ""),
                    "transaction_time" to isoFmt.format(Date(m.date)),
                    "receiver_wallet"  to (m.receiverWallet ?: ""),
                    "sms_body"         to m.body,
                    "scanned_at"       to isoFmt.format(Date())
                )
            }
            is ScanResult.AmountMismatch -> {
                val m = result.message
                mapOf(
                    "sender_phone"     to (m.senderPhone ?: ""),
                    "amount"           to (m.amount ?: 0.0),
                    "expected_amount"  to result.expectedAmount,
                    "found_amount"     to result.foundAmount,
                    "scanned_at"       to isoFmt.format(Date())
                )
            }
            is ScanResult.NotFound -> {
                mapOf(
                    "reason" to result.reason,
                    "scanned_at" to isoFmt.format(Date())
                )
            }
            is ScanResult.Failure -> {
                mapOf(
                    "reason" to result.reason,
                    "scanned_at" to isoFmt.format(Date())
                )
            }
        }

        val failureReason = when (result) {
            is ScanResult.Failure -> result.reason
            is ScanResult.NotFound -> result.reason
            is ScanResult.AmountMismatch -> "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"
            else -> null
        }

        val body = mutableMapOf<String, Any>(
            "device_id" to HeartbeatManager.getDeviceId(context),
            "access_token" to accessToken,
            "refresh_token" to (refreshToken ?: ""),
            "task_id" to task.taskId,
            "request_id" to task.requestId,
            "status" to resultStatus,
            "idempotency_key" to idempotencyKey
        )

        body["result_data"] = resultData
        if (!failureReason.isNullOrEmpty()) body["failure_reason"] = failureReason
        if (!task.paymentOrderId.isNullOrEmpty()) body["payment_order_id"] = task.paymentOrderId
        if (!task.orderExpiresAt.isNullOrEmpty()) body["order_expires_at"] = task.orderExpiresAt

        WebhookSender.sendAdminTaskResult(adminUrl, body) { success, msg, responseBody ->
            Log.d(TAG, "Admin task result sent: $success — $msg")
            if (success) {
                try {
                    val obj = org.json.JSONObject(responseBody)
                    val tokens = obj.optJSONObject("tokens")
                    if (tokens != null) {
                        val newAccess = tokens.optString("access_token")
                        val newRefresh = tokens.optString("refresh_token")
                        val expiresAt = tokens.optLong("expires_at", 0L)
                        if (newAccess.isNotEmpty()) {
                            AdminSession.updateTokens(context, newAccess, newRefresh, expiresAt)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse refreshed tokens: ${e.message}")
                }
            }
            taskResultCallback?.invoke(task, result)
            onSent?.invoke(success)
        }
    }

    /**
     * يعيد إرسال نتيجة محفوظة محلياً (من TaskResultCache) للسيرفر.
     * يستخدم endpoint الأدمن إذا كانت الجلسة نشطة، وإلا يستخدم Webhook.
     */
    fun resendCachedResult(
        context: Context,
        task: Task,
        cached: TaskResultCache.CachedResult,
        webhookUrl: String,
        secret: String
    ) {
        val resultData = cached.resultData?.let {
            try {
                val obj = org.json.JSONObject(it)
                mapOf(
                    "sender_phone"     to obj.optString("sender_phone"),
                    "sender_name"      to obj.optString("sender_name"),
                    "amount"           to obj.optDouble("amount", 0.0),
                    "transaction_id"   to obj.optString("transaction_id"),
                    "transaction_time" to obj.optString("transaction_time"),
                    "receiver_wallet"  to obj.optString("receiver_wallet"),
                    "sms_body"         to obj.optString("sms_body"),
                    "scanned_at"       to obj.optString("scanned_at")
                )
            } catch (e: Exception) { null }
        }

        val result = when (cached.status) {
            "success" -> resultData?.let {
                ScanResult.Success(
                    ScannedMessage(
                        sender = it["sender_phone"] as? String ?: "",
                        senderPhone = it["sender_phone"] as? String,
                        senderName = it["sender_name"] as? String,
                        amount = it["amount"] as? Double,
                        transactionId = it["transaction_id"] as? String,
                        body = it["sms_body"] as? String ?: "",
                        date = parseIsoToMillis(it["scanned_at"] as? String),
                        amountMatch = true,
                        phoneMatch = true,
                        score = 100,
                        receiverWallet = it["receiver_wallet"] as? String
                    )
                )
            } ?: ScanResult.NotFound(cached.failureReason ?: "no cached data")
            "amount_mismatch" -> {
                val obj = cached.resultData?.let { try { org.json.JSONObject(it) } catch (e: Exception) { null } }
                val foundAmount = obj?.optDouble("found_amount", 0.0) ?: 0.0
                val expectedAmount = obj?.optDouble("expected_amount", 0.0) ?: 0.0
                val senderPhone = obj?.optString("sender_phone")
                ScanResult.AmountMismatch(
                    ScannedMessage(
                        sender = senderPhone ?: "",
                        senderPhone = senderPhone,
                        senderName = null,
                        amount = foundAmount,
                        transactionId = null,
                        body = "",
                        date = System.currentTimeMillis(),
                        amountMatch = false,
                        phoneMatch = false,
                        score = 0
                    ),
                    expectedAmount = expectedAmount,
                    foundAmount = foundAmount
                )
            }
            "not_found" -> ScanResult.NotFound(cached.failureReason ?: "لم يتم العثور")
            else -> ScanResult.Failure(cached.failureReason ?: "خطأ تقني")
        }

        sendTaskResult(context, task, result, webhookUrl, secret) { success ->
            if (success) {
                TaskResultCache.remove(context, task.taskId)
            } else {
                TaskResultCache.incrementRetry(context, task.taskId)
            }
        }
    }

    private fun parseIsoToMillis(iso: String?): Long {
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // Callback for UI updates when a task result is sent
    var taskResultCallback: ((task: Task, result: ScanResult) -> Unit)? = null

    private fun isVodafoneCashMessage(body: String): Boolean {
        return KEYWORDS.any { body.contains(it, ignoreCase = true) }
    }

    /**
     * التحقق من أن الرسالة رسالة فودافون كاش رسمية "استلام" (received).
     * لازم تحتوي على مؤشر فودافون، وعبارة استلام، وتحتوي على مبلغ ورقم عملية.
     * رسائل "تم تحويل" (صادرة) مرفوضة لأنها مش تأكيد دفع وارد.
     */
    fun isOfficialVodafoneCashMessage(body: String): Boolean {
        if (MANDATORY_KEYWORDS.none { body.contains(it, ignoreCase = true) }) return false
        if (RECEIVED_KEYWORDS.none { body.contains(it, ignoreCase = true) }) return false
        if (OUTGOING_KEYWORDS.any { body.contains(it, ignoreCase = true) }) return false

        val hasAmount = Regex(
            """(?:تم\s+استلام(?:\s+مبلغ)?|استلام(?:\s+مبلغ)?|استلمت(?:\s+مبلغ)?|مبلغ)\s*[\d,]+\.?\d*\s*(?:جنيه|جنية|egp)""",
            RegexOption.IGNORE_CASE
        ).find(body) != null
        val hasTransaction = Regex(
            """(?:رقم\s+العملية|رقم العملية|transaction|كود\s+المعاملة)[:\s]+([A-Za-z0-9]+)""",
            RegexOption.IGNORE_CASE
        ).find(body) != null
        return hasAmount && hasTransaction
    }

    /**
     * فحص صندوق الرسائل الحقيقي للاختبار — بيرجع كل رسائل فودافون كاش الرسمية.
     */
    fun scanInboxForTest(context: Context): List<ParsedSms> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val results = mutableListOf<ParsedSms>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null,
            Telephony.Sms.DATE + " DESC LIMIT 50"
        ) ?: return emptyList()
        cursor.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                if (isOfficialVodafoneCashMessage(body)) {
                    val parsed = parseSmsBody(body)
                    val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    results.add(parsed.copy(date = date))
                }
            }
        }
        return results
    }

    /**
     * البحث عن رسالة محددة في صندوق الرسائل — بيستخدم المبلغ والرقم والتاريخ.
     * بيرجع قائمة بالرسائل المطابقة مرتبة حسب الأقرب للتاريخ.
     */
    fun searchInboxForTest(context: Context, target: ParsedSms): List<ParsedSms> {
        val all = scanInboxForTest(context)
        val targetPhone = normalizeEgyptianPhone(target.senderPhone ?: "")
        val targetAmount = target.amount ?: 0.0
        return all.filter { sms ->
            val smsPhone = normalizeEgyptianPhone(sms.senderPhone ?: "")
            val amountMatch = targetAmount > 0 && sms.amount != null && kotlin.math.abs(sms.amount - targetAmount) <= 0.01
            val phoneMatch = targetPhone.isNotEmpty() && smsPhone == targetPhone
            amountMatch && phoneMatch
        }.sortedBy { kotlin.math.abs(it.date - target.date) }
    }

    // Enhanced SMS parser — extracts transaction_id, receiver_wallet, sender_name
    /**
     * نسخة عامة من parseSmsBody للاختبار السريع دون قراءة صندوق الرسائل.
     */
    fun testParseSms(text: String): ParsedSms = parseSmsBody(text)

    fun strictMatch(text: String): Boolean {
        val parsed = parseSmsBody(text)
        return isOfficialVodafoneCashMessage(text) &&
                parsed.amount != null &&
                parsed.senderPhone != null &&
                parsed.transactionId != null
    }

    private fun parseSmsBody(text: String): ParsedSms {
        // ── الأولوية: رسالة فودافون كاش المصرية الرسمية ──────────────────────
        // "تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل بإسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13. رقم العملية: 022655099780"
        // "تم استلام مبلغ 5.10 جنيه من 01222692182؛ المسجل بإسم نادر اكرام راغب مينا على رقم محفظتك 01097273680 ..."
        val officialVFRegex = Regex(
            """تم\s+استلام\s+مبلغ\s*([\d,]+\.?\d{0,2})\s*جنيه\s*من\s*(?:رقم\s*)?(\+?0?1[0-9]{9})\s*[:؛.]?\s*المسجل\s+بإسم\s+([A-Za-z][A-Za-z0-9\s]{1,40}|[\u0600-\u06FF\s]{2,40})\s+على\s+رقم\s+محفظتك\s*(\+?0?1[0-9]{9})\s+.*?\s*(?:بتاريخ|تاريخ العملية[:\s]+)\d{1,2}:\d{2}\s+\d{2}-\d{2}-\d{2,4}.*?(?:رقم\s+العملية|رقم العملية)[:\s]+([A-Za-z0-9]+)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val om = officialVFRegex.find(text)
        if (om != null) {
            return ParsedSms(
                senderPhone  = om.groupValues[2].trim(),
                senderName   = om.groupValues[3].trim(),
                amount       = om.groupValues[1].replace(",", "").toDoubleOrNull(),
                transactionId= om.groupValues[5].trim(),
                body         = text,
                date         = System.currentTimeMillis(),
                receiverWallet = om.groupValues[4].trim()
            )
        }

        // ── fallback: باقي أنماط فودافون كاش ────────────────────────────────
        val amountRegexes = listOf(
            Regex("تم استلام مبلغ\\s*([\\d,]+\\.?\\d{0,2})\\s*جنيه"),
            Regex("مبلغ\\s*([\\d,]+\\.?\\d{0,2})\\s*جنيه"),
            Regex("استلمت\\s+(?:من\\s+.+?\\s+)?مبلغ\\s*([\\d,]+\\.?\\d{0,2})"),
            Regex("received\\s+(?:egp\\s+)?([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("egp\\s+([\\d,]+\\.?\\d{0,2})", RegexOption.IGNORE_CASE),
            Regex("([\\d,]+\\.\\d{1,2})\\s*جنيه"),
            Regex("([\\d,]+\\.?\\d{0,2})")
        )
        var amount: Double? = null
        for (re in amountRegexes) {
            val m = re.find(text)
            if (m != null) {
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull()
                if (v != null && v > 0) { amount = v; break }
            }
        }

        // Phone — from SMS sender line e.g. "من 01152210028"
        val phoneRegexes = listOf(
            Regex("من\\s*(\\+?0?1[0-9]{9})"),
            Regex("from\\s*(\\+?\\d[\\d ]{8,14})", RegexOption.IGNORE_CASE),
            Regex("(\\+?20\\s*1\\d{9})"),
            Regex("(01[0-9]{9})")
        )
        var senderPhone: String? = null
        for (re in phoneRegexes) {
            val m = re.find(text)
            if (m != null) { senderPhone = m.groupValues[1].replace("\\s".toRegex(), ""); break }
        }

        // Sender name — "بإسم AHMED REDA على" / "بإسم نادر اكرام على" / "المسجل بإسم"
        val nameRegexes = listOf(
            Regex("(?:المسجل\\s+)?بإسم\\s+([A-Za-z][A-Za-z0-9 ]{1,40})\\s*على"),
            Regex("(?:المسجل\\s+)?بإسم\\s+([\\u0600-\\u06FF ]{2,40})\\s*على"),
            Regex("(?:المسجل\\s+)?باسم\\s+([\\u0600-\\u06FF ]{2,40})\\s+"),
            Regex("from\\s+([A-Za-z][A-Za-z ]{1,30})\\s+on", RegexOption.IGNORE_CASE)
        )
        var senderName: String? = null
        for (re in nameRegexes) {
            val m = re.find(text)
            if (m != null) {
                val candidate = m.groupValues[1].trim()
                if (!candidate.matches(Regex("\\d+"))) { senderName = candidate; break }
            }
        }

        // Receiver wallet — "على رقم محفظتك 01097273680"
        val walletRegexes = listOf(
            Regex("على رقم محفظتك\\s*(0?1[0-9]{9})"),
            Regex("wallet[:\\s]*(\\+?0?1[0-9]{9})", RegexOption.IGNORE_CASE)
        )
        var receiverWallet: String? = null
        for (re in walletRegexes) {
            val m = re.find(text)
            if (m != null) { receiverWallet = m.groupValues[1]; break }
        }

        // Transaction ID — "رقم العملية: 022655099780"
        val txRegexes = listOf(
            Regex("رقم العملية[:\\s]+([A-Za-z0-9]+)"),
            Regex("كود المعاملة[:\\s]+([A-Za-z0-9]+)"),
            Regex("transaction\\s*id[:\\s]+([A-Za-z0-9]+)", RegexOption.IGNORE_CASE),
            Regex("\\b([0-9]{9,20})\\b")
        )
        var transactionId: String? = null
        for (re in txRegexes) {
            val m = re.find(text)
            if (m != null) { transactionId = m.groupValues[1]; break }
        }

        return ParsedSms(senderPhone, senderName, amount, transactionId, text, 0, receiverWallet)
    }

    private fun normalizeEgyptianPhone(raw: String): String {
        val digits = raw.replace("\\D".toRegex(), "")
        return when {
            digits.length == 10 && digits.startsWith("1") -> digits
            digits.length == 11 && digits.startsWith("01") -> digits.substring(1)
            digits.length == 12 && digits.startsWith("20") && digits[2] == '1' -> digits.substring(2)
            digits.length == 13 && digits.startsWith("20") && digits[3] == '1' -> digits.substring(3)
            else -> digits
        }
    }

    private fun isNameMatch(requested: String?, found: String?): Boolean {
        if (requested.isNullOrEmpty() || found.isNullOrEmpty()) return false
        // مقارنة مقتطفة: أول كلمة من الاسم المطلوب تظهر في الاسم الموجود
        val requestedTokens = requested.trim().split(Regex("\\s+"))
        val normalizedFound = found.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s]"), "")
        for (token in requestedTokens) {
            val nToken = token.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06FF]"), "")
            if (nToken.length >= 2 && normalizedFound.contains(nToken)) return true
        }
        return false
    }

    sealed class ScanResult {
        data class Success(val message: ScannedMessage) : ScanResult()
        data class AmountMismatch(val message: ScannedMessage, val expectedAmount: Double, val foundAmount: Double) : ScanResult()
        data class Failure(val reason: String) : ScanResult()
        data class NotFound(val reason: String) : ScanResult()
    }

    data class ScannedMessage(
        val sender: String,
        val senderPhone: String?,
        val senderName: String?,
        val amount: Double?,
        val transactionId: String?,
        val body: String,
        val date: Long,
        val amountMatch: Boolean,
        val phoneMatch: Boolean,
        val score: Int,
        val receiverWallet: String? = null
    )
}
