package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.RawCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host (JVM) unit tests for [SbiSmsParser]. Entry point: `parse(raw: RawCapture): ParsedTxn?`
 * (routed by `canParse`, which requires source == Source.SMS and "SBI" in sender/text).
 *
 * Samples are realistic SBI UPI SMS templates exercising the shared [SmsPatterns] regexes the parser
 * delegates to: amount (Rs./INR), direction (debit vs credit), the 12-digit Ref-No/RRN anchor, the
 * masked "A/c X1234" last-4, and the credit-only available-balance. Money is asserted in paise.
 */
class SbiSmsParserTest {

    private val parser = SbiSmsParser()

    /** Build an SMS RawCapture the way the capture layer would (source == Source.SMS). */
    private fun sms(text: String, sender: String? = "VK-SBIUPI") =
        RawCapture(source = Source.SMS, text = text, sender = sender, capturedAt = 0L)

    // ── routing ───────────────────────────────────────────────────────────────

    @Test fun `canParse accepts an SBI SMS and rejects a non-SBI one`() {
        assertTrue(parser.canParse(sms("Rs.10 debited A/c X1234 Ref No 111122223333", sender = "VK-SBIUPI")))
        // "SBI" can come from the body too, not just the sender.
        assertTrue(parser.canParse(sms("Your SBI A/c was debited by Rs.10", sender = "AD-XYZ")))
        assertEquals(
            "wrong bank → not ours to parse",
            false,
            parser.canParse(sms("HDFC Bank: Rs.10 debited", sender = "AD-HDFC")),
        )
        // A non-SMS source is never routed here.
        val a11y = RawCapture(source = Source.A11Y, text = "SBI Rs.10 debited", capturedAt = 0L)
        assertEquals(false, parser.canParse(a11y))
    }

    // ── debit ─────────────────────────────────────────────────────────────────

    @Test fun `parses a debit with amount, last4 and a Ref-No RRN`() {
        val t = parser.parse(
            sms("Dear SBI User, your A/c X1234-debited by Rs.250.0 on 05Jun24 transfer to MERCHANT Ref No 412345678901. -SBI"),
        )
        assertNotNull(t); t!!
        assertEquals(25_000L, t.amountPaise)            // Rs.250.0 → 25000 paise
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("412345678901", t.rrn)
        assertEquals("1234", t.payerAccountLast4)
        assertEquals("SBI", t.bankLabel)
        assertNull("available balance is captured for credits only", t.availableBalancePaise)
        assertEquals("sms-sbi", t.parserName)
    }

    @Test fun `parses a paise-bearing debit to a VPA`() {
        val t = parser.parse(
            sms("Dear SBI UPI User, Rs.49.50 debited from A/c X4321 and paid to chai@oksbi. UPI Ref No 100200300400."),
        )
        assertNotNull(t); t!!
        assertEquals(4_950L, t.amountPaise)             // Rs.49.50 → 4950 paise
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals("100200300400", t.rrn)
        assertEquals("4321", t.payerAccountLast4)
        assertEquals("chai@oksbi", t.payeeVpa)
        assertNull(t.availableBalancePaise)
    }

    // ── credit ────────────────────────────────────────────────────────────────

    @Test fun `parses a credit with INR amount, RRN, last4 and available balance`() {
        val t = parser.parse(
            sms("Dear Customer, INR 1,500.00 credited to your SBI A/c X1234 on 05Jun24 by UPI Ref No 998877665544. Avl Bal Rs.12,345.67"),
        )
        assertNotNull(t); t!!
        assertEquals(150_000L, t.amountPaise)           // INR 1,500.00 → 150000 paise
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("998877665544", t.rrn)
        assertEquals("1234", t.payerAccountLast4)
        assertEquals(1_234_567L, t.availableBalancePaise)  // Avl Bal Rs.12,345.67 → 1234567 paise
        assertEquals("SBI", t.bankLabel)
    }

    @Test fun `falls back to a bare 12-digit token when no Ref-No anchor is present`() {
        // No "Ref No"/"RRN" keyword — the bare 12-digit RRN fallback must still grab it.
        val t = parser.parse(
            sms("SBI: Rs.75 credited to A/c X9988 by UPI 555544443333. Avl Bal Rs.500.00"),
        )
        assertNotNull(t); t!!
        assertEquals(7_500L, t.amountPaise)
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals("555544443333", t.rrn)
        assertEquals("9988", t.payerAccountLast4)
        assertEquals(50_000L, t.availableBalancePaise)
    }

    // ── misses → null ───────────────────────────────────────────────────────────

    @Test fun `an unrelated SBI SMS with no amount yields null`() {
        // Routed (contains "SBI") but there is no Rs./INR amount → the parser bails to null.
        assertNull(parser.parse(sms("Your OTP for login is 4321. Do not share it with anyone. -SBI")))
    }

    @Test fun `an amount with no debit or credit keyword yields null`() {
        // Has an amount but no direction keyword → direction() returns null → parse returns null.
        assertNull(parser.parse(sms("SBI A/c X1234 balance is Rs.999.00 as on 05Jun24. Ref No 121212121212")))
    }
}
