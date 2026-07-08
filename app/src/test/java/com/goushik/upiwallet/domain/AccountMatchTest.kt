package com.goushik.upiwallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PURE host tests for [AccountMatch] — per-account spend attribution must survive the same bank arriving
 *  under different captured labels (SMS "SBI" vs GPay-sheet "State Bank of India"). */
class AccountMatchTest {

    @Test fun `SBI variants all normalise to the same key`() {
        assertEquals("sbi", AccountMatch.normalizeBank("SBI"))
        assertEquals("sbi", AccountMatch.normalizeBank("State Bank of India"))
    }

    @Test fun `State Bank of India matches the SBI account`() {
        assertTrue(AccountMatch.matches("State Bank of India", "SBI"))
        assertTrue(AccountMatch.matches("SBI", "SBI"))
    }

    @Test fun `HDFC matches HDFC`() {
        assertTrue(AccountMatch.matches("HDFC", "HDFC"))
        assertTrue(AccountMatch.matches("HDFC Bank", "HDFC"))
    }

    @Test fun `different banks do not match`() {
        assertFalse(AccountMatch.matches("HDFC", "SBI"))
        assertFalse(AccountMatch.matches("State Bank of India", "HDFC"))
    }

    @Test fun `a null bank label never matches`() {
        assertFalse(AccountMatch.matches(null, "HDFC"))
    }
}
