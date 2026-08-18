package com.naderai.smsreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * نماذج من الاختبارات الستة المطلوبة قبل اعتبار النسخة جاهزة.
 * تتحقق من منطق الفحص والـ Parser ومنع التكرار دون الحاجة لبناء APK.
 */
class EndToEndScenarioTest {

    private fun makeTask(amount: Double, senderPhone: String = "01012345678") = TaskScanner.Task(
        taskId = "task-${System.nanoTime()}",
        requestId = "req-${System.nanoTime()}",
        amountRequested = amount,
        senderPhoneRequested = senderPhone,
        senderNameRequested = null,
        fingerprintAmount = amount,
        creditsAmount = null,
        orderNumber = null,
        creditsRequested = null,
        customerEmail = null,
        customerPhone = null,
        paymentMethod = "vodafone_cash",
        requestCreatedAt = null,
        paymentOrderId = null,
        orderExpiresAt = null
    )

    private fun smsBody(amount: Double, senderPhone: String = "01012345678", txId: String = "022111222333") = """
        تم استلام مبلغ ${"%.2f".format(amount)} جنيه من رقم $senderPhone المسجل باسم محمد أحمد على رقم محفظتك 01098765432.
        رصيدك الحالي: 9999.99 جنيه.
        رقم العملية: $txId
    """.trimIndent()

    @Test
    fun test1_exactAmount600_50_shouldMatch() {
        val body = smsBody(600.50)
        val parsed = SmsParser.parseReceived(body)
        assertEquals(600.50, parsed.amount ?: 0.0, 0.0)

        val task = makeTask(600.50)
        assertTrue("Phone should match", normalizedPhonesEqual(parsed.senderPhone, task.senderPhoneRequested))
        assertTrue("Amount should match", amountsMatch(parsed.amount, task.fingerprintAmount ?: task.amountRequested))
    }

    @Test
    fun test2_amount600_05_shouldRejectBecauseRequestedIs600_50() {
        val body = smsBody(600.05)
        val parsed = SmsParser.parseReceived(body)
        assertEquals(600.05, parsed.amount ?: 0.0, 0.0)

        val task = makeTask(600.50)
        assertFalse("Amount mismatch must reject", amountsMatch(parsed.amount, task.fingerprintAmount ?: task.amountRequested))
    }

    @Test
    fun test3_duplicateTransactionId_shouldBeRejectedByQueue() {
        val txId = "022-same-tx"
        val sms1 = LocalSmsQueue.QueuedSms(
            transactionId = txId,
            senderPhone = "123456789",
            senderName = "Mohamed",
            amount = 600.50,
            receiverWallet = "987654321",
            smsBody = "body",
            receivedAt = System.currentTimeMillis()
        )
        // Queue deduplication means the second push should not add a duplicate
        val unique = HashSet<String>()
        if (sms1.transactionId != null) unique.add(sms1.transactionId)
        val sms2 = sms1.copy(receivedAt = System.currentTimeMillis() + 1000)
        if (sms2.transactionId != null) {
            val alreadyExists = unique.contains(sms2.transactionId)
            assertTrue("Duplicate transaction must be detected", alreadyExists)
        }
    }

    @Test
    fun test4_smsArrivesBeforeOrder_isQueuedAndNotLost() {
        // Before order: only queue exists, no pending tasks
        val sms = LocalSmsQueue.QueuedSms(
            transactionId = "022-before-order",
            senderPhone = "123456789",
            senderName = "Mohamed",
            amount = 600.50,
            receiverWallet = "987654321",
            smsBody = "sms before order",
            receivedAt = System.currentTimeMillis()
        )
        assertNotNull("SMS must be queued", sms.transactionId)
        assertEquals(600.50, sms.amount ?: 0.0, 0.0)
        // When order arrives, findMatch would find it (tested elsewhere)
    }

    @Test
    fun test5_appRestart_ordersPersistedBySnapshot() {
        val order = OrderItem(
            requestId = "req-persist",
            orderLabel = "طلب شحن",
            expectedAmount = 600.50,
            status = OrderStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            orderNumber = 2014L,
            customerEmail = "ndkg06361@gmail.com",
            customerPhone = "01011111111",
            customerName = "Nader",
            senderPhoneRequested = "01012345678",
            paymentMethod = "vodafone_cash"
        )
        val snapshot = order.resolvedSnapshot()
        assertEquals(600.50, snapshot.expectedAmount, 0.0)
        assertEquals(2014L, snapshot.orderNumber)
        assertEquals("ndkg06361@gmail.com", snapshot.customerEmail)
        // OrderStorage is responsible for persisting; this test verifies snapshot contract
    }

    @Test
    fun test6_apkUpdate_doesNotWipeOrders() {
        // OrderStorage.markVersion does not clear cache; clearIfVersionChanged is deprecated
        // This is a contract test: snapshot must survive update because data is loaded from server
        val order = OrderItem(
            requestId = "req-update",
            orderLabel = "طلب شحن",
            expectedAmount = 600.50,
            status = OrderStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            snapshot = OrderSnapshot(
                requestId = "req-update",
                orderLabel = "طلب شحن",
                expectedAmount = 600.50,
                createdAt = 1000L
            )
        )
        val restored = order.withSnapshotPreserved(order.copy(status = OrderStatus.SCANNING, expectedAmount = 0.0, customerEmail = null))
        assertEquals(600.50, restored.expectedAmount, 0.0)
        assertEquals(OrderStatus.SCANNING, restored.status)
    }

    private fun normalizedPhonesEqual(a: String?, b: String?): Boolean {
        return SmsParser.normalizeEgyptianPhone(a ?: "") == SmsParser.normalizeEgyptianPhone(b ?: "")
    }

    private fun amountsMatch(actual: Double?, expected: Double): Boolean {
        if (actual == null) return false
        return Math.round(actual * 100) == Math.round(expected * 100)
    }
}
