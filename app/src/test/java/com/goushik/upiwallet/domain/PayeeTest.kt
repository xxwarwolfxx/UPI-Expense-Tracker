package com.goushik.upiwallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host tests for the person-vs-business heuristic that drives the recents glyph + the review flag. */
class PayeeTest {

    @Test fun `individual names are detected as people`() {
        for (n in listOf(
            "Ari Chandran S", "S Muniyandi", "Mr Parthiban Jeyakumar", "Ramesh Kumar",
            "Priya Sharma", "J Karthick", "Kumaravelpandian B", "Dr Anita Rao",
        )) {
            assertEquals(n, PayeeKind.PERSON, Payee.kind(n))
            assertTrue(n, Payee.isPerson(n))
        }
    }

    @Test fun `organisation names are detected as businesses`() {
        for (n in listOf(
            "CRED Club", "SMFG India Credit", "Navi Finserv Limited", "Zepto Marketplace Private Limited",
            "Apollo Pharmacy", "Reliance Industries Ltd", "Ram & Sons", "BESCOM Electricity",
        )) {
            assertEquals(n, PayeeKind.BUSINESS, Payee.kind(n))
            assertFalse(n, Payee.isPerson(n))
        }
    }

    @Test fun `a bare phone-number VPA with no name is an individual`() {
        assertTrue(Payee.isPerson(null, "9876543210@ybl"))
        assertTrue(Payee.isPerson(null, "918012345678@okhdfcbank"))
    }

    @Test fun `a merchant-handle VPA with no name is not flagged a person`() {
        assertFalse(Payee.isPerson(null, "razorpay@icici"))
        assertFalse(Payee.isPerson(null, "q83hd7@ybl"))
    }

    @Test fun `null name and vpa is unknown, never a person`() {
        assertEquals(PayeeKind.UNKNOWN, Payee.kind(null, null))
        assertFalse(Payee.isPerson(null, null))
    }

    @Test fun `digit or symbol payloads are not people`() {
        assertFalse(Payee.isPerson("PAYTM-9931"))   // has digits → not a clean name
        assertFalse(Payee.isPerson("BHARATPE.0X2K"))
    }
}
