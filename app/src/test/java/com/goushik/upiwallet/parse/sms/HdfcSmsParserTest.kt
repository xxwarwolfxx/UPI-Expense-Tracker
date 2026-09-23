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
        assertEquals(2, t.parserVersion)
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

    // ── direction comes from the verb, never from the payee's name ─────────────────────────────────

    @Test fun `a payment to a lender whose name ends in Credit is a DEBIT (the real multi-line layout)`() {
        // A real loan EMI was booked as INCOME because bare "credit" matched the payee's name.
        val t = parser.parse(
            sms(
                "Sent Rs.500.00\nFrom HDFC Bank A/C *1234\nTo ACME India Credit\nOn 01/01/26\n" +
                    "Ref 123456789012\nNot You?\nCall 18001234567/SMS BLOCK UPI to 7000000000"
            )
        )
        assertNotNull(t); t!!
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(50_000L, t.amountPaise)
        assertEquals("123456789012", t.rrn)
        assertEquals("1234", t.payerAccountLast4)
    }

    @Test fun `the same lender case on one line is still a DEBIT`() {
        val t = parser.parse(sms("Sent Rs.500.00 From HDFC Bank A/C *1234 To ACME Credit On 01/01/26 Ref 123456789012"))
        assertEquals(Direction.DEBIT, t?.direction)
    }

    @Test fun `paying a credit-card bill over UPI is a DEBIT`() {
        val t = parser.parse(sms("Sent Rs.2,000.00 From HDFC Bank A/C *1234 To ACME Bank Credit Card On 01/01/26 Ref 223344556677"))
        assertEquals(Direction.DEBIT, t?.direction)
    }

    @Test fun `a UPI mandate execution is a DEBIT`() {
        val t = parser.parse(sms("UPI Mandate: Sent Rs.199.00 from HDFC Bank A/c 1234 To ACME Stores 01/01/26 Ref 334455667788"))
        assertEquals(Direction.DEBIT, t?.direction)
        assertEquals("334455667788", t?.rrn)
    }

    // ── things that moved no money are not transactions ─────────────────────────────────────────────

    @Test fun `mandate and autopay notices are not transactions`() {
        assertNull(parser.parse(sms("E-Mandate! Rs.199.00 will be deducted on 02/01/26 10:00:00 For ACME Stores mandate UMN abc123@okhdfcbank Maintain Balance -HDFC Bank")))
        assertNull(parser.parse(sms("HDFC Bank: Upcoming mandate set for 02/01/26 10:00 AM ,your account will be debited with Rs 499.00towards ACME Stores for UPI Mandate.kindly maintain sufficient Balance")))
        assertNull(parser.parse(sms("AutoPay (E-mandate) Reminder! Your ACME Amt Rs.649.00 will be deducted from HDFC Bank Debit Card xx1234 ON: 02/01/26 SI Hub ID: Ab12Cd34 TnC")))
        assertNull(parser.parse(sms("Your EMI of Rs.2,500 on HDFC Bank loan a/c no. 123456 is due on 05-Jan-26.")))
    }

    @Test fun `a collect request is not a payment`() {
        assertNull(parser.parse(sms("HDFC Bank: ACME Stores has requested Rs. 250.00 from you through UPI. To authorize debit from your account please login to your UPI App.")))
    }

    @Test fun `declined and failed card attempts are not spends`() {
        assertNull(parser.parse(sms("Dear Customer, your txn of Rs.99.00 on HDFC Bank Debit Card ending 1234 is declined due to incorrect CVV/Expiry date.")))
        assertNull(parser.parse(sms("Failed! Transaction amount: Rs.99.00 HDFC Bank Debit Card 1234 is disabled for Domestic Online payment.")))
        assertNull(parser.parse(sms("AutoPay (E-mandate)Declined! ACME Current Txn Amt Rs.649.00 On 01/01/26 Via HDFC Bank Debit Card xx1234 TnC")))
    }

    @Test fun `a credit-card offer with an amount is not income`() {
        assertNull(parser.parse(sms("Dear Ramesh, Lifetime FREE HDFC Bank Credit Card + Rs.500 Amazon voucher! Apply now: hdfcbk.io/a/xyz T&C")))
    }

    @Test fun `debit-card spends and successful card autopay still count`() {
        assertEquals(Direction.DEBIT, parser.parse(sms("ALERT:Rs.120.00 spent via Debit Card xx1234 at ACME on Aug 1 26 9:00AM without PIN/OTP.Not you?Call 18001234567."))?.direction)
        assertEquals(Direction.DEBIT, parser.parse(sms("AutoPay (E-mandate) Successful! For ACME Current Txn Amt: Rs.649.00 Date:01/01/26 Via: E-mandate on HDFC Bank Debit Card xx1234 TnC"))?.direction)
    }

    // ── the sender header, not the body, decides whose message it is ────────────────────────────────

    @Test fun `only an HDFC bank header routes here`() {
        val body = "Sent Rs.500.00 From HDFC Bank A/C *1234 To Ramesh Kumar On 01/01/26 Ref 123456789012"
        assertTrue(parser.canParse(sms(body, sender = "VM-HDFCBK-S")))
        assertTrue(parser.canParse(sms(body, sender = "AXhdfcbk")))
        assertTrue(parser.canParse(sms(body, sender = "JM-HDFCBN")))
        assertFalse("a phone number is anyone", parser.canParse(sms(body, sender = "+919800000000")))
        assertFalse("a shop that mentions HDFC is not HDFC", parser.canParse(sms(body, sender = "AD-ACMESH")))
        assertFalse("no sender, no trust", parser.canParse(sms(body, sender = "")))
    }
}
