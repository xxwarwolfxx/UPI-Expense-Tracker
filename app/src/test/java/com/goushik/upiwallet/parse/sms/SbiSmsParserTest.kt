package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.ParserRegistry
import com.goushik.upiwallet.parse.RawCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host (JVM) unit tests for [SbiSmsParser]. Entry point: `parse(raw: RawCapture): ParsedTxn?`
 * (routed by `canParse`, which requires source == Source.SMS and an SBI sender HEADER — see [BankSenders]).
 *
 * The first debit fixture is the shape of SBI's real current UPI alert (scrubbed: made-up digits and a
 * placeholder payee); it has no Rs/INR before the amount, which is why SBI debits never parsed before v2.
 * Money is asserted in paise.
 */
class SbiSmsParserTest {

    private val parser = SbiSmsParser()

    /** Build an SMS RawCapture the way the capture layer would (source == Source.SMS). */
    private fun sms(text: String, sender: String? = "VK-SBIUPI") =
        RawCapture(source = Source.SMS, text = text, sender = sender, capturedAt = 0L)

    // ── routing ───────────────────────────────────────────────────────────────

    @Test fun `canParse accepts an SBI header and rejects everything else`() {
        assertTrue(parser.canParse(sms("Rs.10 debited A/c X1234 Ref No 111122223333", sender = "VK-SBIUPI")))
        assertTrue(parser.canParse(sms("Rs.10 debited A/c X1234", sender = "BZCBSSBI")))
        assertTrue(parser.canParse(sms("Rs.10 debited A/c X1234", sender = "JK-SBIUPI-S")))
        // "SBI" in the body is not enough any more — the sender header decides.
        assertFalse(parser.canParse(sms("Your SBI A/c was debited by Rs.10", sender = "AD-XYZ")))
        assertFalse("a phone number is never a bank", parser.canParse(sms("Your SBI A/c was debited by Rs.10", sender = "+919812345678")))
        assertFalse(parser.canParse(sms("Your SBI A/c was debited by Rs.10", sender = "9812345678")))
        assertEquals(
            "wrong bank → not ours to parse",
            false,
            parser.canParse(sms("HDFC Bank: Rs.10 debited", sender = "AD-HDFCBK")),
        )
        // A non-SMS source is never routed here.
        val a11y = RawCapture(source = Source.A11Y, text = "SBI Rs.10 debited", sender = "VK-SBIUPI", capturedAt = 0L)
        assertEquals(false, parser.canParse(a11y))
    }

    // ── the current SBI UPI debit alert (no currency prefix) ─────────────────────────────────────────

    @Test fun `parses SBI's current UPI debit alert — amount, RRN from Refno, payee, account`() {
        val t = parser.parse(
            sms("Dear UPI user A/C X1234 debited by 250.0 on date 12Sep26 trf to Ramesh Kumar Refno 612345678901 If not u? call-1800000000 for other services-18001234-SBI"),
        )
        assertNotNull(t); t!!
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(25_000L, t.amountPaise)
        assertEquals("612345678901", t.rrn)
        assertEquals("Ramesh Kumar", t.payeeName)
        assertEquals("1234", t.payerAccountLast4)
        assertEquals("SBI", t.bankLabel)
        assertEquals(2, t.parserVersion)
    }

    @Test fun `the SBI UPI debit reads comma and paise amounts`() {
        val t = parser.parse(
            sms("Dear UPI user A/C X9876 debited by 1,250.50 on date 01Jan26 trf to ACME Stores Refno 600011112222 If not u? call-1800000000 for other services-18001234-SBI"),
        )
        assertEquals(125_050L, t?.amountPaise)
        assertEquals("ACME Stores", t?.payeeName)
    }

    // ── older debit templates ─────────────────────────────────────────────────

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

    @Test fun `debited-then-credited IMPS is a DEBIT — the earliest verb wins`() {
        val t = parser.parse(
            sms("Dear Customer, Your a/c no. XXXXXXXX1234 is debited for Rs.500.00 on 01-01-26 and a/c XXXXXXX5678 credited (IMPS Ref no 612345678901).If not done by you, call 1800 -SBI", sender = "AD-SBIINB"),
        )
        assertEquals(Direction.DEBIT, t?.direction)
        assertEquals(50_000L, t?.amountPaise)
    }

    @Test fun `core-banking has-a-debit and has-a-credit keep their direction`() {
        assertEquals(Direction.DEBIT, parser.parse(sms("Dear Customer, Your A/C XXXXX1234 has a debit by transfer of Rs 250.00 on 01/01/26. Avl Bal Rs 999.00.-SBI", sender = "BZCBSSBI"))?.direction)
        assertEquals(Direction.CREDIT, parser.parse(sms("Your A/C XXXXX1234 has a credit by Transfer of Rs 100.00 on 01/01/26 by Bank. Avl Bal Rs 999.00.", sender = "BZCBSSBI"))?.direction)
    }

    @Test fun `an SBI debit-card spend still counts`() {
        val t = parser.parse(
            sms("Dear Customer, transaction number 1234 for Rs.500.00 by SBI Debit Card X1234 done at 99999 on 01Jan26 at 10:00:00. Your updated available balance is Rs.999.00.", sender = "BXATMSBI"),
        )
        assertEquals(Direction.DEBIT, t?.direction)
        assertEquals(50_000L, t?.amountPaise)
    }

    @Test fun `a debit-card e-mandate that went through counts, one that failed does not`() {
        assertEquals(Direction.DEBIT, parser.parse(sms("Dear Customer, Payment of Rs 199 for ACME e-mandate SiHubId Ab12Cd34 has been processed successfully on your SBI Debit card ending 1234.", sender = "VMSBIDCM"))?.direction)
        assertNull(parser.parse(sms("Dear Customer, Payment of Rs 199 for ACME for e-mandate SiHubId Ab12Cd34 could not be processed successfully on your SBI Debit card ending 1234.", sender = "VM-SBIBNK-S")))
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

    // ── SBI Card: spends are debits, nothing on the card is income ────────────────────────────────────

    @Test fun `an SBI Card spend is a DEBIT even though it says Credit Card`() {
        val t = parser.parse(sms("Rs.499.00 spent on your SBI Credit Card ending 1234 at ACME on 01/01/26. Trxn. not done by you? Report at sbicard.com", sender = "JDSBICRD"))
        assertEquals(Direction.DEBIT, t?.direction)
        assertEquals(49_900L, t?.amountPaise)
        assertEquals(Direction.DEBIT, parser.parse(sms("Transaction of Rs.199.00 at ACME against E-mandate (SiHub ID - Ab12Cd34) registered by you at merchant has been debited to your SBI Credit Card ending 1234 on 01-01-26.", sender = "VMSBICRD"))?.direction)
    }

    @Test fun `a card bill payment or cashback credited to the card is not income`() {
        assertNull(parser.parse(sms("We have received payment of Rs.5,000.00 via BBPS & the same has been credited to your SBI Credit Card. Your available limit is Rs.50,000.00.", sender = "VM-SBICRD")))
        assertNull(parser.parse(sms("Rs. 99.00 has been credited to your SBI Credit Card xxxx1234 towards reversal/cashback from ACME for trxn. dated 01/01/26", sender = "ADSBICRD")))
    }

    @Test fun `card due notices and offers are nothing`() {
        assertNull(parser.parse(sms("Dear Cardholder, your payment of INR 199 at ACME is due on 05/01/26 and will be processed through your credit card ending 1234 as per e-Mandate.", sender = "JDSBICRD")))
        assertNull(parser.parse(sms("Dear SBI Cardholder, you have consumed 90% of your Credit Limit and your balance Credit Limit is Rs.1,000.", sender = "JDSBICRD")))
    }

    @Test fun `a reward offer from the bank's own account header is not a spend`() {
        // "spent" is a debit verb and "Rs 500" an amount, but no alert earns points or names a minimum.
        assertNull(parser.parse(sms("Dear Customer-SBI Festive Bonanza ends on 31 Oct 2026. Earn 5X points on min Rs 500 spent on Debit Card. T&C-State Bank of India", sender = "ADSBIBNK")))
        assertNull(parser.parse(sms("Dear Customer, earn 400 reward points on a minimum INR 2000 spent with your SBI Debit Card. T&Cs apply -SBI", sender = "VM-SBIBNK")))
    }

    @Test fun `a real card spend is untouched by the offer markers`() {
        val r = parser.parse(sms("Rs.500 spent on your SBI Credit Card ending 1234 at KESTREL STORES on 05/01/26. Trxn. not done by you? Report at https://sbicard.com/Dispute", sender = "JDSBICRD"))
        assertNotNull(r)
        assertEquals(Direction.DEBIT, r!!.direction)
        assertEquals(50_000L, r.amountPaise)
    }

    // ── misses → null ───────────────────────────────────────────────────────────

    @Test fun `an AutoPay is-scheduled notice is not a spend (and is not claimed by HDFC for its VPA)`() {
        val body = "Dear UPI User, UPI AutoPay for ACME Stores debit of Rs.499.00 is scheduled on .05/01/26 acme@okhdfcbank. Please ensure sufficient balance in your account. -SBI"
        assertNull(parser.parse(sms(body, sender = "JK-SBIUPI-S")))
        assertFalse(HdfcSmsParser().canParse(sms(body, sender = "JK-SBIUPI-S")))
        assertNull(ParserRegistry.default().parse(sms(body, sender = "JK-SBIUPI-S")))
    }

    @Test fun `an unrelated SBI SMS with no amount yields null`() {
        // Routed (SBI header) but there is no Rs./INR amount → the parser bails to null.
        assertNull(parser.parse(sms("Your OTP for login is 4321. Do not share it with anyone. -SBI")))
    }

    @Test fun `an OTP that names an amount is still not a transaction`() {
        assertNull(parser.parse(sms("OTP for Fund Tfr of Rs. 500 from Ac X1234 to Ramesh is 123456. Do not share with anyone. - SBI", sender = "JDSBYONO")))
    }

    @Test fun `an amount with no debit or credit keyword yields null`() {
        // Has an amount but no direction keyword → direction() returns null → parse returns null.
        assertNull(parser.parse(sms("SBI A/c X1234 balance is Rs.999.00 as on 05Jun24. Ref No 121212121212")))
    }
}
