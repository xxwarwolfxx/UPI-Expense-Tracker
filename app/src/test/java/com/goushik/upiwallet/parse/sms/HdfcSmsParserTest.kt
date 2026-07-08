package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.RawCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host (JVM) unit tests for [HdfcSmsParser]. The parser is pure Kotlin — Regex (java.util.regex) +
 * [com.goushik.upiwallet.util.Money] (Math.round / String.format) — with NO android.util.* usage, so it
 * runs on a plain JVM. We feed real-shaped HDFC UPI SMS bodies and assert only the fields each regex
 * actually fills: amountPaise (rupees*100), direction, rrn (12-digit RRN/UPI Ref), and the
 * payerAccountLast4 / payeeVpa / availableBalancePaise the templates expose. A malformed body → null.
 */
class HdfcSmsParserTest {

    private val parser = HdfcSmsParser()

    /** Build an SMS RawCapture the way the capture layer does (Source.SMS, an HDFC sender). */
    private fun sms(body: String, sender: String = "HDFCBK"): RawCapture =
        RawCapture(source = Source.SMS, text = body, sender = sender, capturedAt = 0L)

    // ── debits ─────────────────────────────────────────────────────────────────

    @Test fun `Sent debit captures amount, DEBIT, last4 and a 12-digit Ref`() {
        val raw = sms(
            "Sent Rs.1,234.00 From HDFC Bank A/c x1234 To JOHN DOE On 03-06 " +
                "Ref 412345678901 Not You? Call 18002586161"
        )
        assertTrue("HDFC sms should route to this parser", parser.canParse(raw))
        val t = parser.parse(raw)
        assertNotNull(t)
        t!!
        assertEquals(123400L, t.amountPaise)            // 1,234.00 rupees → paise
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("1234", t.payerAccountLast4)
        assertEquals("412345678901", t.rrn)
        assertEquals("HDFC", t.bankLabel)
        assertNull("avl balance is only read for credits", t.availableBalancePaise)
        assertEquals("sms-hdfc", t.parserName)
        assertEquals(1, t.parserVersion)
    }

    @Test fun `debited body captures masked last4, VPA and UPI Ref No`() {
        val raw = sms(
            "Rs.500.00 debited from a/c **1234 on 04-06-24 to VPA john@okhdfcbank " +
                "UPI Ref No 223344556677. Avl bal Rs.10,000.00"
        )
        val t = parser.parse(raw)
        assertNotNull(t)
        t!!
        assertEquals(50000L, t.amountPaise)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("1234", t.payerAccountLast4)
        assertEquals("john@okhdfcbank", t.payeeVpa)
        assertEquals("223344556677", t.rrn)
        assertNull("avl balance is only read for credits", t.availableBalancePaise)
    }

    // ── credits ────────────────────────────────────────────────────────────────

    @Test fun `credited body captures amount, CREDIT, last4, VPA, Ref and Avl bal`() {
        val raw = sms(
            "Rs.2,500.00 credited to a/c XX5678 on 05-06-24 by VPA alice@oksbi. " +
                "UPI Ref 998877665544. Avl bal Rs.25,750.50"
        )
        val t = parser.parse(raw)
        assertNotNull(t)
        t!!
        assertEquals(250000L, t.amountPaise)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("5678", t.payerAccountLast4)
        assertEquals("alice@oksbi", t.payeeVpa)
        assertEquals("998877665544", t.rrn)
        assertEquals(2575050L, t.availableBalancePaise)  // Avl bal Rs.25,750.50 → paise (credit only)
    }

    @Test fun `INR-prefixed credit parses amount and available balance`() {
        val raw = sms(
            "INR 750.00 credited to HDFC Bank A/c XX9012 on 02-06-24. " +
                "UPI Ref No 100200300400. Avl Bal INR 5,000.00"
        )
        val t = parser.parse(raw)
        assertNotNull(t)
        t!!
        assertEquals(75000L, t.amountPaise)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("9012", t.payerAccountLast4)
        assertEquals("100200300400", t.rrn)
        assertEquals(500000L, t.availableBalancePaise)
    }

    // ── routing + the malformed / unrelated path ────────────────────────────────

    @Test fun `a non-HDFC sms does not route to the HDFC parser`() {
        val raw = RawCapture(
            source = Source.SMS,
            text = "Sent Rs.500.00 From SBI A/c x9999 Ref 111122223333",
            sender = "SBIINB",
            capturedAt = 0L,
        )
        assertFalse(parser.canParse(raw))
    }

    @Test fun `an HDFC sms with no amount returns null without crashing`() {
        // Routes to HDFC (contains "HDFC") but has no Rs/INR amount → AMOUNT miss → null.
        val raw = sms("HDFC Bank: Your OTP for login is 482910. Do not share it with anyone.")
        assertTrue(parser.canParse(raw))
        assertNull(parser.parse(raw))
    }

    @Test fun `an HDFC sms with an amount but no debit or credit keyword returns null`() {
        // Amount present, but no direction keyword (debited/sent/credited/...) → direction null → null.
        val raw = sms("HDFC Bank: Your account statement of Rs.1,000.00 is ready to view.")
        assertTrue(parser.canParse(raw))
        assertNull(parser.parse(raw))
    }
}
