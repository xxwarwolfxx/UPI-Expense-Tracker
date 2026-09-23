package com.goushik.upiwallet.parse.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sender gate: a bank is recognised by its registered header, in every spelling the phone shows. */
class BankSendersTest {

    @Test fun `HDFC headers in every observed spelling`() {
        for (s in listOf("AD-HDFCBK", "VM-HDFCBK-S", "JM-HDFCBK-T", "ADHDFCBK", "AXhdfcbk", "ADHDFCBk", "QPhdfcbn", "CPHDFCBN", "HDFCBK")) {
            assertEquals(s, BankSenders.HDFC, BankSenders.bankOf(s))
        }
    }

    @Test fun `SBI headers — UPI, core banking, ATM, net banking, YONO, SBI Card`() {
        for (s in listOf("AD-SBIUPI", "JK-SBIUPI-S", "BZCBSSBI", "BXATMSBI", "BZSBIINB", "VM-SBIBNK-S", "SBIBNK", "JDSBYONO", "JDSBICRD", "AXMYSBIC")) {
            assertEquals(s, BankSenders.SBI, BankSenders.bankOf(s))
        }
    }

    @Test fun `phone numbers, blanks and non-bank headers are nobody`() {
        for (s in listOf("+919800000000", "9800000000", "51469", "", "   ", "AD-LIFSTL", "AXCREDIN", "AD-HDFCGI", "VMSBIRWZ", "AD-SBICGV", "AT650137")) {
            assertNull(s, BankSenders.bankOf(s))
        }
        assertNull(BankSenders.bankOf(null))
    }

    @Test fun `only SBI Card headers are card issuers`() {
        assertTrue(BankSenders.isCardIssuer("JDSBICRD"))
        assertTrue(BankSenders.isCardIssuer("VM-SBICRD-S"))
        assertTrue(BankSenders.isCardIssuer("AXMYSBIC"))
        assertFalse(BankSenders.isCardIssuer("AD-SBIUPI"))
        assertFalse(BankSenders.isCardIssuer("AD-HDFCBK"))
        assertFalse(BankSenders.isCardIssuer(null))
    }
}
