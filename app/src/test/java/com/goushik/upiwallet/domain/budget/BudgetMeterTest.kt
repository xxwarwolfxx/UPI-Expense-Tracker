package com.goushik.upiwallet.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [budgetMeter] — the money-stack widget's state. % left + lit tiers depletes from full;
 * the band follows % USED (calm/near/over), decoupled from how full the stack looks.
 */
class BudgetMeterTest {

    private val tiers = 6

    @Test fun `no cap set is empty and calm`() {
        val m = budgetMeter(spentPaise = 5_000, limitPaise = 0, tiers = tiers)
        assertEquals(0, m.pctLeft)
        assertEquals(0, m.litTiers)
        assertEquals(BudgetBand.CALM, m.band)
        assertFalse(m.over)
    }

    @Test fun `nothing spent is full stack and 100 percent left`() {
        val m = budgetMeter(spentPaise = 0, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(100, m.pctLeft)
        assertEquals(tiers, m.litTiers)
        assertEquals(BudgetBand.CALM, m.band)
    }

    @Test fun `71 percent used is calm with 29 left and 2 tiers`() {
        // ₹8,540 of ₹12,000 → 71% used, 29% left → round(0.29*6)=2 lit, still calm (<80% used).
        val m = budgetMeter(spentPaise = 854_000, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(29, m.pctLeft)
        assertEquals(2, m.litTiers)
        assertEquals(BudgetBand.CALM, m.band)
        assertFalse(m.over)
    }

    @Test fun `88 percent used is amber near`() {
        val m = budgetMeter(spentPaise = 1_056_000, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(BudgetBand.NEAR, m.band)
        assertEquals(12, m.pctLeft)
        assertTrue(m.litTiers >= 1) // any headroom lights at least one note
        assertFalse(m.over)
    }

    @Test fun `a sliver left still lights one tier`() {
        // 97% used → round(0.03*6)=0, but a positive remainder must never read as an empty stack.
        val m = budgetMeter(spentPaise = 1_164_000, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(1, m.litTiers)
        assertFalse(m.over)
    }

    @Test fun `over budget is empty stack coral and zero left`() {
        val m = budgetMeter(spentPaise = 1_312_000, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(0, m.pctLeft)
        assertEquals(0, m.litTiers)
        assertEquals(BudgetBand.OVER, m.band)
        assertTrue(m.over)
    }

    @Test fun `exactly at the cap is over with an empty stack`() {
        val m = budgetMeter(spentPaise = 1_200_000, limitPaise = 1_200_000, tiers = tiers)
        assertEquals(0, m.litTiers)
        assertEquals(BudgetBand.OVER, m.band)
        assertTrue(m.over)
    }
}
