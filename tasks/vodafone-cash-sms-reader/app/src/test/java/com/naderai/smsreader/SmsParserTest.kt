package com.naderai.smsreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {

    @Test
    fun officialMessage_extractsReceivedAmountNotWalletBalance() {
        val body = """
            تم استلام مبلغ 5.18 جنيه من رقم 01012345678 المسجل باسم محمد أحمد على رقم محفظتك 01098765432.
            رصيدك الحالي: 606.82 جنيه.
            رقم العملية: 022768543034
        """.trimIndent()

        assertTrue(SmsParser.isOfficialReceivedMessage(body))
        val parsed = SmsParser.parseReceived(body)

        assertEquals(5.18, parsed.amount ?: 0.0, 0.0)
        assertEquals("123456789", parsed.senderPhone)
        assertEquals("محمد أحمد", parsed.senderName)
        assertEquals("987654321", parsed.receiverWallet)
        assertEquals("022768543034", parsed.transactionId)
    }

    @Test
    fun officialOldFormatSingleLine_stillParsesReceivedAmount() {
        val body = "تم استلام مبلغ 300.00 جنيه من 01152210028؛ المسجل باسم AHMED REDA على رقم محفظتك 01097273680 بتاريخ 15:54 26-08-13. رقم العملية: 022655099780"

        assertTrue(SmsParser.isOfficialReceivedMessage(body))
        val parsed = SmsParser.parseReceived(body)

        assertEquals(300.00, parsed.amount ?: 0.0, 0.0)
        assertEquals("1152210028", parsed.senderPhone)
        assertEquals("AHMED REDA", parsed.senderName)
        assertEquals("097273680", parsed.receiverWallet)
        assertEquals("022655099780", parsed.transactionId)
    }

    @Test
    fun outgoingMessage_isRejected() {
        val body = "تم تحويل مبلغ 50.00 جنيه من محفظتك 01098765432 إلى 01012345678"
        assertFalse(SmsParser.isOfficialReceivedMessage(body))
    }

    @Test
    fun nonVodafoneMessage_isRejected() {
        val body = "Your bank account balance is 1000.00 EGP"
        assertFalse(SmsParser.isOfficialReceivedMessage(body))
    }

    @Test
    fun fallbackAmount_extractsOnlyReceivedAmount() {
        val body = "تم استلام مبلغ 600.50 جنيه من رقم 01012345678. رصيدك الحالي: 999.00 جنيه. رقم العملية: 111222333"
        val parsed = SmsParser.parseReceived(body)
        assertEquals(600.50, parsed.amount ?: 0.0, 0.0)
        assertEquals("111222333", parsed.transactionId)
    }

    @Test
    fun amount_ignoresWalletBalance() {
        // A message that only contains balance and no received amount should not return a balance
        val body = "رصيدك الحالي: 606.82 جنيه. فودافون كاش."
        assertFalse(SmsParser.isOfficialReceivedMessage(body))
        val parsed = SmsParser.parseReceived(body)
        assertNull(parsed.amount)
        assertNull(parsed.transactionId)
    }
}
