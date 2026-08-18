package com.naderai.smsreader

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderSnapshotTest {

    @Test
    fun snapshot_isPreservedWhenServerSendsEmptyFields() {
        val original = OrderItem(
            requestId = "req-123",
            orderLabel = "طلب شحن",
            expectedAmount = 600.50,
            status = OrderStatus.PENDING,
            createdAt = 1000L,
            updatedAt = 1000L,
            orderNumber = 2014L,
            creditsRequested = 1,
            customerEmail = "ndkg06361@gmail.com",
            customerPhone = "01011111111",
            customerName = "Nader",
            senderPhoneRequested = "01012345678",
            paymentMethod = "vodafone_cash"
        )

        val snapshot = original.resolvedSnapshot()
        val emptyUpdate = OrderItem(
            requestId = "req-123",
            orderLabel = "",
            expectedAmount = 0.0,
            status = OrderStatus.SCANNING,
            createdAt = 0L,
            updatedAt = 2000L,
            customerEmail = null,
            customerPhone = null,
            customerName = null,
            senderPhoneRequested = null,
            paymentMethod = null,
            snapshot = null
        )

        val merged = original.withSnapshotPreserved(emptyUpdate)
        assertEquals(600.50, merged.expectedAmount, 0.0)
        assertEquals(2014L, merged.orderNumber)
        assertEquals(1, merged.creditsRequested)
        assertEquals("ndkg06361@gmail.com", merged.customerEmail)
        assertEquals("01011111111", merged.customerPhone)
        assertEquals("Nader", merged.customerName)
        assertEquals("01012345678", merged.senderPhoneRequested)
        assertEquals("vodafone_cash", merged.paymentMethod)
        assertEquals(OrderStatus.SCANNING, merged.status)
    }

    @Test
    fun statusMapping_fromServerStrings() {
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromString("approved"))
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromString("confirmed"))
        assertEquals(OrderStatus.COMPLETED, OrderStatus.fromString("success"))
        assertEquals(OrderStatus.MATCHED, OrderStatus.fromString("verified"))
        assertEquals(OrderStatus.MATCHED, OrderStatus.fromString("found"))
        assertEquals(OrderStatus.FAILED, OrderStatus.fromString("rejected"))
        assertEquals(OrderStatus.FAILED, OrderStatus.fromString("failed"))
        assertEquals(OrderStatus.AMOUNT_MISMATCH, OrderStatus.fromString("amount_mismatch"))
        assertEquals(OrderStatus.DUPLICATE, OrderStatus.fromString("duplicate"))
        assertEquals(OrderStatus.EXPIRED, OrderStatus.fromString("expired"))
    }

    @Test
    fun terminalStates_dontAcceptReopen() {
        assertTrue(OrderStatus.COMPLETED.isTerminal())
        assertTrue(OrderStatus.FAILED.isTerminal())
        assertTrue(OrderStatus.DUPLICATE.isTerminal())
        assertTrue(OrderStatus.EXPIRED.isTerminal())
        assertFalse(OrderStatus.SCANNING.isTerminal())
        assertFalse(OrderStatus.MATCHING.isTerminal())
    }
}
