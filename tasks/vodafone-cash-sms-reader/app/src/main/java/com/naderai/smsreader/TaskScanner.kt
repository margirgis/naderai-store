package com.naderai.smsreader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections

/**
 * Scans device SMS inbox for a task's matching Vodafone Cash message.
 */
object TaskScanner {

    private const val TAG = "TaskScanner"
    const val MAX_SCAN_ATTEMPTS = 5
    private const val SCAN_INTERVAL_MS = 20_000L

    private val KEYWORDS = listOf(
        "فودافون كاش", "vodafone cash", "استلمت", "لقد استلمت",
        "تم استلام", "received egp", "you have received", "تحويل", "مبلغ"
    )

    // ── الشرط الذهبي: الـ ADDRESS (اسم/رقم المُرسِل كما يظهر في صندوق الرسائل) ──
    // فودافون كاش الرسمي دائماً يُرسِل من أحد هذه الأسماء أو الأرقام
    // أي رسالة من شماره عادية أو اسم غير معروف = غير رسمية
    val OFFICIAL_SENDER_ADDRESSES = setOf(
        "vodafone", "vodafonecash", "vf-cash", "vfcash",
        "vf cash", "vc", "voda", "vodafone cash",
        "2010", "2020", "2880", "16888", "888"   // أرقام الخدمة الرسمية لفودافون مصر
    )

    // الكلمات التي تدل على رسالة صادرة (ليست استلام) — نطابقها في بداية الرسالة فقط
    private val OUTGOING_KEYWORDS = listOf(
        "تم تحويل",
        "تم سحب",
        "قمت بتحويل",
        "you have sent",
        "you transferred"
    )

    /** يتحقق أن الـ ADDRESS هو فودافون الرسمي — الحاجز الأساسي */
    fun isOfficialVodafoneSender(smsAddress: String): Boolean {
        val lower = smsAddress.trim().lowercase()
        return OFFICIAL_SENDER_ADDRESSES.any { lower.contains(it) }
    }

    /** حماية من تشغيل أكثر من Scanner على نفس المهمة في نفس الوقت */
    private val activeScanJobs = Collections.synchronizedSet(mutableSetOf<String>())

    fun isScanning(taskId: String): Boolean = activeScanJobs.contains(taskId)

    fun clearScanLock(taskId: String) {
        activeScanJobs.remove(taskId)
    }

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
        // اسم صاحب الحساب الحقيقي (profiles.full_name) — ليس اسم المُحوِّل
        val customerName: String? = null,
        val paymentMethod: String?,
        val requestCreatedAt: String?,
        // بيانات payment_order الجديدة
        val paymentOrderId: String?,
        val orderExpiresAt: String?,
        // Phase-1: timestamps لتتبع lifecycle الطلب
        val queuedAt: String?     = null,   // وقت دخول الطلب الـ queue في الـ server
        val dispatchedAt: String? = null,   // وقت إرسال الطلب لهذا الجهاز
        val receivedAt: String?   = null    // وقت استلام الجهاز للطلب (يُسجَّل محلياً)
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
        // منع تكرار الفحص: نفس task لا يبدأ Scan إلا مرة واحدة في نفس الوقت
        if (!activeScanJobs.add(task.taskId)) {
            Log.d(TAG, "Scan already in progress for task ${task.taskId}; skipping duplicate")
            return
        }

        val handler = CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Scan crashed for task ${task.taskId}: ${e.message}", e)
            activeScanJobs.remove(task.taskId)
            AppState.updateOrderStatus(task.requestId, OrderStatus.FAILED)
            AppState.addNotification(DeviceNotification(
                title = "تعطّل الفحص",
                message = "خطأ داخلي: ${e.message}",
                type = NotificationType.ERROR,
                referenceId = task.requestId
            ))
            sendTaskResult(
                context = context,
                task = task,
                result = ScanResult.Failure("خطأ داخلي: ${e.message}"),
                webhookUrl = webhookUrl,
                secret = secret,
                onSent = { _ ->
                    onResult?.invoke(ScanResult.Failure("خطأ داخلي: ${e.message}"), false)
                    Unit
                }
            )
        }

        CoroutineScope(Dispatchers.IO + SupervisorJob() + handler).launch {
            var attempt = 0
            var lastResult: ScanResult = ScanResult.NotFound("لم يبدأ الفحص")
            try {
                while (attempt < MAX_SCAN_ATTEMPTS) {
                    attempt++
                    // تحديث UI: المحاولة الحالية
                    AppState.updateOrderScanProgress(task.requestId, attempt, MAX_SCAN_ATTEMPTS, 0)
                    Log.i(TAG, "SCAN_ATTEMPT | order=${task.requestId} attempt=$attempt/${MAX_SCAN_ATTEMPTS} task=${task.taskId}")
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
                if (lastResult is ScanResult.Success) {
                    lastResult.message.transactionId?.let { LocalSmsQueue.remove(context, it) }
                }
                sendTaskResult(
                    context = context,
                    task = task,
                    result = lastResult,
                    webhookUrl = webhookUrl,
                    secret = secret,
                    onSent = { success ->
                        onResult?.invoke(lastResult, success)
                        Unit
                    },
                    onServerResponse = { scanStatus, ok ->
                        // السيرفر هو من يقرر — نحدث الحالة بناءً على رده
                        Log.i(TAG, "SERVER_CONFIRM | order=${task.requestId} task=${task.taskId} scan_status=$scanStatus ok=$ok")
                        AppState.onServerConfirm(
                            requestId   = task.requestId,
                            taskId      = task.taskId,
                            scanStatus  = scanStatus,
                            ok          = ok,
                            orderNumber = task.orderNumber
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Scan exception for task ${task.taskId}: ${e.message}", e)
                throw e
            } finally {
                activeScanJobs.remove(task.taskId)
                Log.d(TAG, "Scan ended for task ${task.taskId}")
            }
        }
    }

    private fun scanInbox(context: Context, task: Task): ScanResult {
        val traceId = "${task.taskId}-${System.currentTimeMillis()}"
        val scanStart = System.currentTimeMillis()

        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return ScanResult.Failure("إذن قراءة الرسائل غير ممنوح")
        }

        // ── EVENT: SCAN_STARTED ──────────────────────────────────────────────
        OrderEventLogger.log(
            event = "SCAN_STARTED",
            orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
            status = null,
            details = "trace=$traceId task=${task.taskId} amount=${task.amountRequested} phone=${task.senderPhoneRequested}"
        )

        val requestedPhone       = task.senderPhoneRequested?.trim().orEmpty()
        val normalizedRequested  = if (requestedPhone.isNotEmpty()) normalizeEgyptianPhone(requestedPhone) else ""
        // Phase-2: إذا لم يوجد phone نستمر بـ amount-only (ليس رفض مباشر)
        val phoneRequired        = normalizedRequested.isNotEmpty()

        // ── EVENT: SMS_SEARCH_STARTED ─────────────────────────────────────────
        OrderEventLogger.log(
            event = "SMS_SEARCH_STARTED",
            orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
            status = null,
            details = "trace=$traceId phoneRequired=$phoneRequired"
        )

        // ── فحص الطابور المحلي أولاً (SMS وصلت قبل task) ────────────────────
        val queued = LocalSmsQueue.findMatch(context, task)
        if (queued != null) {
            val dur = System.currentTimeMillis() - scanStart
            OrderEventLogger.log(
                event = "SMS_MATCH_FOUND",
                orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                status = "local_queue",
                details = "trace=$traceId source=LocalQueue txId=${queued.transactionId} amount=${queued.amount} duration_ms=$dur"
            )
            LocalSmsQueue.remove(context, queued.transactionId)
            return ScanResult.Success(
                ScannedMessage(
                    sender        = queued.senderPhone ?: normalizedRequested,
                    senderPhone   = queued.senderPhone,
                    senderName    = queued.senderName,
                    amount        = queued.amount,
                    transactionId = queued.transactionId,
                    body          = queued.smsBody,
                    date          = queued.receivedAt,
                    amountMatch   = true,
                    phoneMatch    = phoneRequired && queued.senderPhone == normalizedRequested ||
                                    !phoneRequired,
                    score         = 100,
                    receiverWallet = queued.receiverWallet
                )
            )
        }

        val cursor = context.contentResolver.query(
            android.provider.Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                android.provider.Telephony.Sms.ADDRESS,
                android.provider.Telephony.Sms.BODY,
                android.provider.Telephony.Sms.DATE
            ),
            null, null,
            android.provider.Telephony.Sms.DATE + " DESC"
        ) ?: return ScanResult.Failure("لا يمكن قراءة صندوق الرسائل")

        // نافذة زمنية: 24 ساعة — موحّدة مع DB (confirm_payment_order MAX_SMS_AGE=24h)
        val SMS_MAX_AGE_MS = 24L * 60 * 60 * 1000
        val orderCreatedMs: Long? = task.requestCreatedAt?.let {
            runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(it.take(19))?.time
            }.getOrNull()
        }
        val targetAmount = task.fingerprintAmount ?: task.amountRequested

        val candidates = mutableListOf<ScannedMessage>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val senderAddr = c.getString(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)) ?: "unknown"
                val body       = c.getString(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)) ?: ""
                val smsDate    = c.getLong(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE))

                // ── EVENT: SOURCE_VALIDATION ──────────────────────────────────
                val senderValid = isOfficialVodafoneSender(senderAddr)
                if (!senderValid) {
                    Log.d(TAG, "SOURCE_VALIDATION | trace=$traceId addr=$senderAddr result=REJECT reason=invalid_sender_address")
                    continue
                }
                OrderEventLogger.log(
                    event = "SOURCE_VALIDATION",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = "ok", details = "trace=$traceId addr=$senderAddr"
                )

                // ── EVENT: TIMESTAMP_CHECK ────────────────────────────────────
                val smsAgeMs = System.currentTimeMillis() - smsDate
                val windowStart = orderCreatedMs?.let { it - 60_000L } ?: (System.currentTimeMillis() - SMS_MAX_AGE_MS)
                val timestampOk = smsDate >= windowStart && smsAgeMs <= SMS_MAX_AGE_MS
                if (!timestampOk) {
                    OrderEventLogger.log(
                        event = "TIMESTAMP_CHECK",
                        orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                        status = "REJECT", details = "trace=$traceId age_min=${smsAgeMs/60000} reason=expired"
                    )
                    continue
                }
                OrderEventLogger.log(
                    event = "TIMESTAMP_CHECK",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = "ok", details = "trace=$traceId age_min=${smsAgeMs/60000}"
                )

                if (!SmsParser.isOfficialReceivedMessage(body)) continue

                // ── Parse باستخدام ParseResult الموحّد ───────────────────────
                val parsed = SmsParser.parse(body, originatingAddress = senderAddr, smsDateMs = smsDate)
                if (!parsed.success) {
                    Log.w(TAG, "SMS_PARSE_FAILED | trace=$traceId reason=${parsed.reason} addr=$senderAddr")
                    OrderEventLogger.log(
                        event = "SMS_PARSE_FAILED",
                        orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                        status = parsed.reason, details = "trace=$traceId addr=$senderAddr"
                    )
                    continue
                }
                OrderEventLogger.log(
                    event = "SMS_PARSE_SUCCESS",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = "ok",
                    details = "trace=$traceId tx=${parsed.transactionId} amount=${parsed.amount} phone=${parsed.senderPhone}"
                )

                // ── EVENT: AMOUNT_CHECK ───────────────────────────────────────
                val amountMatch = parsed.amount != null && targetAmount > 0 &&
                    Math.round(targetAmount * 100) == Math.round(parsed.amount * 100)
                OrderEventLogger.log(
                    event = "AMOUNT_CHECK",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = if (amountMatch) "ok" else "MISMATCH",
                    details = "trace=$traceId expected=$targetAmount found=${parsed.amount}"
                )

                // ── EVENT: SENDER_CHECK ───────────────────────────────────────
                val normalizedSenderInSms = parsed.senderPhone ?: normalizeEgyptianPhone(senderAddr)
                val phoneMatch = if (phoneRequired) normalizedSenderInSms == normalizedRequested else true
                OrderEventLogger.log(
                    event = "SENDER_CHECK",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = if (phoneMatch) "ok" else "MISMATCH",
                    details = "trace=$traceId expected=$normalizedRequested found=$normalizedSenderInSms phoneRequired=$phoneRequired"
                )

                // ── EVENT: WALLET_CHECK ───────────────────────────────────────
                val walletMatch = !parsed.receiverWallet.isNullOrEmpty() // configured wallet checked server-side
                OrderEventLogger.log(
                    event = "WALLET_CHECK",
                    orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                    status = if (walletMatch) "ok" else "MISSING",
                    details = "trace=$traceId wallet=${parsed.receiverWallet}"
                )

                val nameMatch = isNameMatch(task.senderNameRequested, parsed.senderName)
                val score = (if (amountMatch) 2 else 0) +
                            (if (phoneMatch && phoneRequired) 2 else if (!phoneRequired && amountMatch) 1 else 0) +
                            (if (nameMatch) 1 else 0) +
                            (if (walletMatch) 1 else 0)

                candidates.add(ScannedMessage(
                    sender         = senderAddr,
                    senderPhone    = normalizedSenderInSms,
                    senderName     = parsed.senderName,
                    amount         = parsed.amount,
                    transactionId  = parsed.transactionId,
                    body           = body,
                    date           = smsDate,
                    amountMatch    = amountMatch,
                    phoneMatch     = phoneMatch,
                    score          = score,
                    receiverWallet = parsed.receiverWallet
                ))
            }
        }

        Log.i(TAG, "SCAN_INBOX_DONE | trace=$traceId order=${task.requestId} candidates=${candidates.size}")

        // ── أفضل تطابق ────────────────────────────────────────────────────────
        // phone + amount معاً هو الوضع المثالي
        // إذا لا يوجد phone في الطلب: amount وحده كافٍ (مع SOURCE_VALIDATION إلزامي)
        val best = candidates.maxByOrNull { it.score }

        val fullMatch   = best != null && best.amountMatch && (best.phoneMatch || !phoneRequired)
        val phoneOnlyM  = candidates.filter { it.phoneMatch && !it.amountMatch }

        if (fullMatch) {
            val dur = System.currentTimeMillis() - scanStart
            OrderEventLogger.log(
                event = "SMS_MATCH_FOUND",
                orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                status = "ok",
                details = "trace=$traceId source=Inbox txId=${best!!.transactionId} amount=${best.amount} phone=${best.senderPhone} score=${best.score} duration_ms=$dur"
            )
            return ScanResult.Success(best)
        }

        if (phoneOnlyM.isNotEmpty()) {
            val closest = phoneOnlyM.maxByOrNull { it.score }!!
            Log.w(TAG, "MATCH_AMOUNT_MISMATCH | trace=$traceId order=${task.requestId} phone=${closest.senderPhone} found=${closest.amount} expected=$targetAmount")
            OrderEventLogger.log(
                event = "AMOUNT_CHECK",
                orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
                status = "amount_mismatch",
                details = "trace=$traceId found=${closest.amount} expected=$targetAmount"
            )
            return ScanResult.AmountMismatch(
                message = closest,
                foundAmount = closest.amount ?: 0.0,
                expectedAmount = targetAmount
            )
        }

        val dur = System.currentTimeMillis() - scanStart
        Log.w(TAG, "MATCH_NOT_FOUND | trace=$traceId order=${task.requestId} candidates=${candidates.size} duration_ms=$dur")
        return if (candidates.isEmpty())
            ScanResult.NotFound("not_found: لم يتم العثور على رسائل فودافون كاش في الجهاز")
        else
            ScanResult.NotFound("not_found: ${candidates.size} رسالة بدون مطابقة تامة")
    }

    fun sendTaskResult(
        context: Context,
        task: Task,
        result: ScanResult,
        webhookUrl: String,
        secret: String,
        onSent: ((Boolean) -> Unit)? = null,
        notifyUi: Boolean = true,
        onServerResponse: ((scanStatus: String, ok: Boolean) -> Unit)? = null
    ) {
        val idempotencyKey = "${task.taskId}-${task.requestId}"
        val resultStatus = when (result) {
            is ScanResult.Success -> "success"
            is ScanResult.AmountMismatch -> "amount_mismatch"
            is ScanResult.NotFound -> "not_found"
            is ScanResult.Failure -> "failure"
        }

        val accessToken = if (AdminSession.isLoggedIn(context)) AdminSession.accessToken(context) else null
        if (!accessToken.isNullOrEmpty()) {
            val adminUrl = SupabaseConfig.getAdminTaskResultUrl(
                context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(MainActivity.KEY_WEBHOOK_URL, null)
            )
            if (!adminUrl.isNullOrEmpty()) {
                Log.d(TAG, "Sending task result via admin endpoint for task ${task.taskId}")
                sendAdminTaskResult(context, adminUrl, task, result, idempotencyKey, resultStatus, onSent, notifyUi, onServerResponse) { success ->
                    if (!success && webhookUrl.isNotEmpty() && secret.isNotEmpty()) {
                        Log.w(TAG, "Admin endpoint failed, falling back to webhook for task ${task.taskId}")
                        sendViaWebhook(context, task, result, webhookUrl, secret, resultStatus, idempotencyKey, onSent, notifyUi)
                    } else {
                        onSent?.invoke(success)
                    }
                }
                return
            }
        }

        if (webhookUrl.isEmpty() || secret.isEmpty()) {
            Log.w(TAG, "No webhook or admin config available; cannot send task result for ${task.taskId}")
            if (notifyUi) taskResultCallback?.invoke(task, result)
            onSent?.invoke(false)
            return
        }

        sendViaWebhook(context, task, result, webhookUrl, secret, resultStatus, idempotencyKey, onSent, notifyUi)
    }

    private fun sendViaWebhook(
        context: Context,
        task: Task,
        result: ScanResult,
        webhookUrl: String,
        secret: String,
        resultStatus: String,
        idempotencyKey: String,
        onSent: ((Boolean) -> Unit)? = null,
        notifyUi: Boolean = true
    ) {
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
                "transaction_id"   to (m.transactionId),
                "transaction_time" to smsDateIso,
                "receiver_wallet"  to (m.receiverWallet ?: ""),
                "sms_body"         to m.body,
                "scanned_at"       to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
            )
        }

        if (result is ScanResult.Failure)        body["failure_reason"] = result.reason
        if (result is ScanResult.NotFound)       body["failure_reason"] = result.reason
        if (result is ScanResult.AmountMismatch) body["failure_reason"] = "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"

        if (!task.paymentOrderId.isNullOrEmpty()) body["payment_order_id"] = task.paymentOrderId
        if (!task.orderExpiresAt.isNullOrEmpty())  body["order_expires_at"] = task.orderExpiresAt

        WebhookSender.sendJsonWithBody(webhookUrl, secret, body) { success, msg, _ ->
            Log.d(TAG, "Webhook task result sent: $success — $msg")
            if (notifyUi) taskResultCallback?.invoke(task, result)
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
        onSent: ((Boolean) -> Unit)? = null,
        notifyUi: Boolean = true,
        onServerResponse: ((scanStatus: String, ok: Boolean) -> Unit)? = null,
        onComplete: ((Boolean) -> Unit)? = null
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
                    "transaction_id"   to (m.transactionId),
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
            is ScanResult.NotFound -> mapOf("reason" to result.reason, "scanned_at" to isoFmt.format(Date()))
            is ScanResult.Failure  -> mapOf("reason" to result.reason, "scanned_at" to isoFmt.format(Date()))
        }

        val failureReason = when (result) {
            is ScanResult.Failure        -> result.reason
            is ScanResult.NotFound       -> result.reason
            is ScanResult.AmountMismatch -> "مبلغ غير مطابق: وجد ${result.foundAmount} والمطلوب ${result.expectedAmount}"
            else -> null
        }

        val body = mutableMapOf<String, Any>(
            "device_id"       to HeartbeatManager.getDeviceId(context),
            "access_token"    to accessToken,
            "refresh_token"   to (refreshToken ?: ""),
            "task_id"         to task.taskId,
            "request_id"      to task.requestId,
            "status"          to resultStatus,
            "idempotency_key" to idempotencyKey
        )
        body["result_data"] = resultData
        if (!failureReason.isNullOrEmpty()) body["failure_reason"] = failureReason
        if (!task.paymentOrderId.isNullOrEmpty()) body["payment_order_id"] = task.paymentOrderId
        if (!task.orderExpiresAt.isNullOrEmpty())  body["order_expires_at"] = task.orderExpiresAt

        // ── EVENT: DUPLICATE_CHECK (قبل الإرسال) ───────────────────────
        val txIdForDuplicateCheck = (result as? ScanResult.Success)?.message?.transactionId
        val verifyStart = System.currentTimeMillis()
        OrderEventLogger.log(
            event = "DUPLICATE_CHECK",
            orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
            status = if (txIdForDuplicateCheck != null) "pending" else "no_tx_id",
            details = "txId=$txIdForDuplicateCheck — server will authoritative-check"
        )

        // ── EVENT: VERIFY_SUBMITTED ─────────────────────────────────────
        OrderEventLogger.log(
            event = "VERIFY_SUBMITTED",
            orderId = task.requestId, orderNumber = task.orderNumber, deviceId = null,
            status = resultStatus,
            details = "taskId=${task.taskId} idempotencyKey=$idempotencyKey txId=$txIdForDuplicateCheck"
        )

        WebhookSender.sendAdminTaskResult(adminUrl, body) { success, msg, responseBody ->
            val verifyDur = System.currentTimeMillis() - verifyStart
            Log.d(TAG, "Admin task result sent: $success — $msg responseBody=${responseBody.take(200)}")
            val orderNum = task.orderNumber
            val reqId    = task.requestId
            if (success) {
                try {
                    val obj = org.json.JSONObject(responseBody)
                    val serverOk         = obj.optBoolean("ok", false)
                    val serverScanStatus = obj.optString("scan_status", "").ifEmpty {
                        if (serverOk) "confirmed" else "rejected"
                    }
                    Log.d(TAG, "[SERVER_DECISION] task=${task.taskId} scan_status=$serverScanStatus ok=$serverOk")

                    // ── EVENT: VERIFY_RESULT ──────────────────────────────
                    OrderEventLogger.log(
                        event = "VERIFY_RESULT",
                        orderId = reqId, orderNumber = orderNum, deviceId = null,
                        status = serverScanStatus,
                        details = "ok=$serverOk duration_ms=$verifyDur txId=$txIdForDuplicateCheck"
                    )

                    // ── EVENT: DUPLICATE_CHECK نتيجة ─────────────────────
                    if (serverScanStatus == "duplicate") {
                        OrderEventLogger.log(
                            event = "DUPLICATE_CHECK",
                            orderId = reqId, orderNumber = orderNum, deviceId = null,
                            status = "DUPLICATE",
                            details = "txId=$txIdForDuplicateCheck — رُفض من السيرفر"
                        )
                    }

                    if (serverOk) {
                        OrderEventLogger.serverSuccess(reqId, orderNum, 200, responseBody)
                    } else {
                        OrderEventLogger.orderRejected(reqId, orderNum, serverScanStatus)
                        OrderDiagnosticsLog.log(
                            OrderDiagnosticsLog.EventType.SERVER_RESPONSE_FAIL,
                            orderNum, reqId, task.taskId,
                            details = "scan_status=$serverScanStatus",
                            serverCode = 200, serverResponse = responseBody
                        )
                    }
                    onServerResponse?.invoke(serverScanStatus, serverOk)
                    val tokens = obj.optJSONObject("tokens")
                    if (tokens != null) {
                        val newAccess  = tokens.optString("access_token")
                        val newRefresh = tokens.optString("refresh_token")
                        val expiresAt  = tokens.optLong("expires_at", 0L)
                        if (newAccess.isNotEmpty()) {
                            AdminSession.updateTokens(context, newAccess, newRefresh, expiresAt)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse server response: ${e.message}")
                    OrderEventLogger.log(
                        event = "VERIFY_RESULT",
                        orderId = reqId, orderNumber = orderNum, deviceId = null,
                        status = "parse_error", details = "duration_ms=$verifyDur err=${e.message}"
                    )
                    OrderDiagnosticsLog.log(
                        OrderDiagnosticsLog.EventType.GENERIC_ERROR,
                        orderNum, reqId, task.taskId,
                        details = "parse error: ${e.message}", serverResponse = responseBody
                    )
                }
            } else {
                val httpCode = runCatching {
                    responseBody.toIntOrNull() ?: msg.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                }.getOrDefault(0)
                OrderEventLogger.log(
                    event = "VERIFY_RESULT",
                    orderId = reqId, orderNumber = orderNum, deviceId = null,
                    status = "network_error",
                    details = "http=$httpCode duration_ms=$verifyDur msg=$msg"
                )
                OrderEventLogger.serverError(reqId, orderNum, httpCode, responseBody, msg)
            }
            if (notifyUi) taskResultCallback?.invoke(task, result)
            onSent?.invoke(success)
            onComplete?.invoke(success)
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
        secret: String,
        onSent: ((Boolean) -> Unit)? = null
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

        sendTaskResult(context, task, result, webhookUrl, secret, onSent = { success ->
            if (success) {
                TaskResultCache.remove(context, task.taskId)
            } else {
                TaskResultCache.incrementRetry(context, task.taskId)
            }
            onSent?.invoke(success)
        }, notifyUi = false)
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
     * يجب أن تحتوي على مؤشر فودافون + عبارة استلام/استلمت + مبلغ.
     * رقم العملية ليس شرطاً في التصفية لأن parseSmsBody يستخرجه لاحقاً.
     * رسائل "تم تحويل" (صادرة) مرفوضة لأنها مش تأكيد دفع وارد.
     */
    fun isOfficialVodafoneCashMessage(body: String): Boolean = SmsParser.isOfficialReceivedMessage(body)

    /**
     * فحص صندوق الرسائل الحقيقي للاختبار — بيرجع كل رسائل فودافون كاش الرسمية.
     */
    fun scanInboxForTest(context: Context): List<ParsedSms> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val results = mutableListOf<ParsedSms>()
        val cursor = context.contentResolver.query(
            android.provider.Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
            null, null,
            android.provider.Telephony.Sms.DATE + " DESC LIMIT 50"
        ) ?: return emptyList()
        cursor.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)) ?: ""
                val body = c.getString(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)) ?: ""
                val date = c.getLong(c.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE))
                // نفس شرط الـ sender الرسمي كما في مسار الطلب الحقيقي
                if (!isOfficialVodafoneSender(address)) {
                    Log.d(TAG, "[TestInbox] Skip non-Vodafone sender: $address")
                    continue
                }
                if (SmsParser.isOfficialReceivedMessage(body)) {
                    val parsed = SmsParser.parseReceived(body)
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
        val targetPhone = SmsParser.normalizeEgyptianPhone(target.senderPhone ?: "")
        val targetAmount = target.amount ?: 0.0
        return all.filter { sms ->
            val smsPhone = SmsParser.normalizeEgyptianPhone(sms.senderPhone ?: "")
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

    private fun parseSmsBody(text: String): ParsedSms = SmsParser.parseReceived(text)

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
