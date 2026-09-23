package com.goushik.upiwallet.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ScreenShape] decides whether a screen is asking for money or listing payments that already happened.
 *
 * Fixtures mirror the SHAPE of the real device dumps with fake names and amounts — the real corpus stays
 * out of git (see [com.goushik.upiwallet.capture.CaptureCorpusTest]).
 */
class ScreenShapeTest {

    /** A live Google Pay PIN sheet, node-flattened. This is the shape of all 155 bank-proven captures. */
    private val paySheet = """
        Logo
        HDFC Bank
        Bank Name
        Masked Account Number
        Close
        Pay ₹87.00
        To Ramesh Kumar
        Enter your PIN
        Never enter your UPI PIN to receive money
        1
        2
        3
        Pay
    """.trimIndent()

    @Test fun `a live pay sheet is not a list`() {
        assertNull(ScreenShape.describesPayments(paySheet))
    }

    @Test fun `a real sheet's 'Add a message (optional)' must not read as chat chrome`() {
        // The regression this whole class was nearly broken by: a first-pass token list used the bare word
        // "message", which flagged five GENUINE pay sheets, because Google Pay's own sheet says exactly this.
        val sheet = "Total payable\n₹1\nPay ₹1\nTo Ramesh Kumar\nHDFC Bank\nAdd a message (optional)"
        assertNull("a pay sheet's own copy must never look like a chat", ScreenShape.describesPayments(sheet))
    }

    @Test fun `a chat with several timestamps is a list`() {
        val chat = "Ramesh Kumar\n₹100\n3:45 pm\n₹100\n4:02 pm\n₹100\n6:18 pm\nType a message"
        assertNotNull(ScreenShape.describesPayments(chat))
    }

    @Test fun `a day-grouped history is a list`() {
        val history = "Transactions\n₹450\n11 Aug\n₹120\n09 Aug\n₹75\n02 Aug"
        assertNotNull(ScreenShape.describesPayments(history))
    }

    @Test fun `two different outcome words mean a list of outcomes`() {
        val list = "₹500 Sent\n₹90 Received\n₹40 Received"
        assertNotNull(ScreenShape.describesPayments(list))
    }

    @Test fun `one outcome word alone is not a list — a success screen has exactly one`() {
        assertNull(ScreenShape.describesPayments("₹87 Paid\nTo Ramesh Kumar\nHDFC Bank"))
    }

    @Test fun `history-only chrome is enough on its own`() {
        for (token in listOf("UPI transaction ID", "Pay again", "Type a message", "Split expense")) {
            assertNotNull("\"$token\" only appears on a history surface",
                ScreenShape.describesPayments("₹87\nTo Ramesh Kumar\n$token"))
        }
    }

    @Test fun `one timestamp duplicated by its contentDescription is not a list`() {
        // collectText appends both a node's text and its contentDescription, which routinely repeat —
        // a live sheet showing ONE visible time must not be rejected because the tree said it twice.
        assertNull(ScreenShape.describesPayments("Pay ₹87\nTo Ramesh Kumar\nHDFC Bank\n3:45 pm\n3:45 pm"))
    }

    @Test fun `a 24-hour chat is still a list — no am-pm needed`() {
        val chat = "Ramesh Kumar\n₹100\n15:45\n₹100\n16:02\n₹100\n18:18"
        assertNotNull("24-hour devices keep the chat defense", ScreenShape.describesPayments(chat))
    }

    @Test fun `the reason names the signal that fired, for logcat`() {
        assertEquals("history chrome \"Pay again\"",
            ScreenShape.describesPayments("₹87\nTo Ramesh Kumar\nPay again"))
    }
}
