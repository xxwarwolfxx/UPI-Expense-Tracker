package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared rules on their own: direction, amount, the not-a-transaction filter, the UPI-payment test. */
class SmsPatternsTest {

    @Test fun `bare nouns no longer decide direction`() {
        assertNull(SmsPatterns.direction("Get a Rs.500 voucher with your new Credit Card"))
        assertNull(SmsPatterns.direction("Save Rs.200 with your Debit Card"))
    }

    @Test fun `the payee segment is ignored when reading direction`() {
        // A payee called "Received Traders" must not turn a sent payment into income, even when the
        // payee line comes before the verb (then "earliest verb wins" alone would pick "Received").
        assertEquals(Direction.DEBIT, SmsPatterns.direction("Rs.10.00\nTo Received Traders\nsent from HDFC Bank A/C *1234\nRef 123456789012"))
        // …same on one line, where the segment ends at "On".
        assertEquals(Direction.DEBIT, SmsPatterns.direction("To Received Traders On 01/01/26: Rs.10 sent from HDFC Bank A/C *1234"))
        // The real HDFC layout (verb first, payee on its own line).
        assertEquals(Direction.DEBIT, SmsPatterns.direction("Sent Rs.10.00\nFrom HDFC Bank A/C *1234\nTo ACME India Credit\nOn 01/01/26\nRef 123456789012"))
    }

    @Test fun `when both kinds of verb appear the earliest wins`() {
        assertEquals(Direction.DEBIT, SmsPatterns.direction("A/c XX1234 is debited for Rs.500 and a/c XX5678 credited"))
        assertEquals(Direction.CREDIT, SmsPatterns.direction("Rs.500 credited to A/c XX1234; earlier Rs.20 debited as charges"))
    }

    @Test fun `a word that merely contains a verb is not the verb`() {
        assertNull(SmsPatterns.direction("Rs.10 unsent reminder"))
    }

    @Test fun `amount is verb-anchored first, then Rs or INR`() {
        assertEquals("250.0", SmsPatterns.amount("A/C X1234 debited by 250.0 on date 12Sep26"))
        assertEquals("1,250.50", SmsPatterns.amount("A/C X1234 credited by 1,250.50 on date 12Sep26"))
        assertEquals("99.00", SmsPatterns.amount("Sent Rs.99.00 From HDFC Bank"))
        assertEquals("500", SmsPatterns.amount("is debited for Rs.500 on 01-01-26"))
        assertNull(SmsPatterns.amount("your account was debited"))
    }

    @Test fun `future-tense notices, bills, OTPs, collect requests and failures are not transactions`() {
        val notices = listOf(
            "UPI AutoPay for ACME debit of Rs.499.00 is scheduled on .05/01/26",
            "Rs.500 will be debited on 01/01/26",
            "E-Mandate! Rs.199.00 will be deducted on 02/01/26",
            "EMI of Rs. 2,500 due on 05/01/26",
            "Your payment of INR 199 is due and will be processed",
            "Pre-debit notification: Rs.99",
            "Total Amt Due: Rs 999 payable by 05/01/26",
            "Dear Ramesh, charges of Rs 99 on your loan is pending. Pay Now. Please ignore if paid",
            "Mandate Registration- UMRN ABCD0000001 for Rs 5000 issued to ACME received today.",
            "Mandate reference no: ABCD0000001 with value Rs. 5000 is received today for processing.",
            "OTP for Fund Tfr of Rs. 500 is 123456",
            "123456 is your OTP for Rs.500",
            "Your One-Time Password for the payment of Rs.500",
            "ACME has requested Rs. 250 from you through UPI",
            "your txn of Rs.99 is declined",
            "Transaction failed! For Rs.99",
            "Payment of Rs 199 could not be processed successfully",
        )
        for (n in notices) assertTrue(n, SmsPatterns.isNotATransaction(n))
    }

    @Test fun `real transactions are not mistaken for notices`() {
        val real = listOf(
            "Sent Rs.500.00 From HDFC Bank A/C *1234 To Ramesh Kumar On 01/01/26 Ref 123456789012",
            "ALERT:Rs.120 spent via Debit Card xx1234 at ACME without PIN/OTP.",
            "Dear UPI user A/C X1234 debited by 250.0 on date 12Sep26 trf to Ramesh Kumar Refno 612345678901",
            "Credit Alert! Rs.100.00 credited to HDFC Bank A/c XX1234 on 01-01-26 from VPA ramesh@okaxis (UPI 612345678901)",
            "ECS/NACH dishonored in Acc XXXXX1234 due to insufficient funds. Rs.500 debited to account as return charges.",
        )
        for (r in real) assertFalse(r, SmsPatterns.isNotATransaction(r))
    }

    @Test fun `a screen-paid UPI debit names a VPA or UPI and is not a mandate`() {
        assertTrue(SmsPatterns.isScreenPaidUpi("Sent Rs.5 From HDFC Bank A/C *1234 To Ramesh Kumar On 01/01/26 Ref 123456789012 Not You? SMS BLOCK UPI to 7000000000", null))
        assertTrue(SmsPatterns.isScreenPaidUpi("Dear UPI user A/C X1234 debited by 250.0", null))
        assertTrue(SmsPatterns.isScreenPaidUpi("Rs 99 debited to VPA ramesh@okaxis", "ramesh@okaxis"))
        assertFalse(SmsPatterns.isScreenPaidUpi("UPI Mandate: Sent Rs.199.00 from HDFC Bank A/c 1234 To ACME Stores", null))
        assertFalse(SmsPatterns.isScreenPaidUpi("AutoPay (E-mandate) Successful! For ACME Current Txn Amt: Rs.649.00", null))
        assertFalse(SmsPatterns.isScreenPaidUpi("Rs.120 spent via Debit Card xx1234 at ACME", null))
    }
}
