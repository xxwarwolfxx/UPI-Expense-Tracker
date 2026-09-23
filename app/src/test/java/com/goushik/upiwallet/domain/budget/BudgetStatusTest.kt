package com.goushik.upiwallet.domain.budget

import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [budgetStatus]. Spend uses the SAME window + predicate as Insights/Home, so the bar
 * can never disagree with the totals; the thresholds drive the once-per-period nudge.
 */
class BudgetStatusTest {

    private val ownVpas = setOf("bram@oksbi")
    private val ownNames = setOf("bram")
    private val now = 1_718_452_800_000L // ~2024-06-15, mid-month
    private val day = 86_400_000L

    private fun monthly(limit: Long) = BudgetEntity(period = "MONTH", limitPaise = limit, updatedAt = now)

    @Test fun `under 80 percent is calm`() {
        val txns = listOf(debit(1, 39_000, now))
        val s = budgetStatus(monthly(100_000), txns, ownVpas, ownNames, now)
        assertEquals(39, s.pct)
        assertFalse(s.isNear); assertFalse(s.isOver)
        assertEquals(0, s.reachedThreshold())
        assertEquals(61_000L, s.remainingPaise)
    }

    @Test fun `at 80 percent is near and reaches the 80 threshold`() {
        val txns = listOf(debit(1, 50_000, now), debit(2, 34_000, now - 3_600_000))
        val s = budgetStatus(monthly(100_000), txns, ownVpas, ownNames, now)
        assertEquals(84, s.pct)
        assertTrue(s.isNear); assertFalse(s.isOver)
        assertEquals(80, s.reachedThreshold())
        assertEquals(16_000L, s.remainingPaise)
    }

    @Test fun `over the cap is over and reaches the 100 threshold`() {
        val txns = listOf(debit(1, 107_000, now))
        val s = budgetStatus(monthly(100_000), txns, ownVpas, ownNames, now)
        assertEquals(107, s.pct)
        assertTrue(s.isOver)
        assertEquals(100, s.reachedThreshold())
        assertEquals(-7_000L, s.remainingPaise)
    }

    @Test fun `spend outside the period is not counted`() {
        val txns = listOf(
            debit(1, 50_000, now),
            debit(2, 90_000, now - 40 * day), // previous month — excluded from the MONTH window
        )
        val s = budgetStatus(monthly(100_000), txns, ownVpas, ownNames, now)
        assertEquals(50_000L, s.spentPaise)
        assertEquals(50, s.pct)
    }

    @Test fun `credits and discarded rows do not count toward a budget`() {
        val txns = listOf(
            debit(1, 50_000, now),
            credit(2, 99_000, now),                                  // income, not spend
            debit(3, 40_000, now, status = TxnStatus.DISCARDED),     // removed
        )
        val s = budgetStatus(monthly(100_000), txns, ownVpas, ownNames, now)
        assertEquals(50_000L, s.spentPaise)
    }

    // ── the nudge's words: percent only, and "reached" at exactly the cap ──

    @Test fun `exactly at the cap the nudge says reached, never over by zero`() {
        val s = budgetStatus(monthly(100_000), listOf(debit(1, 100_000, now)), ownVpas, ownNames, now)
        assertEquals(100, s.reachedThreshold())
        val (title, text) = budgetNudgeCopy(s)
        assertEquals("At your monthly limit", title)
        assertEquals("You've reached your monthly budget.", text)
    }

    @Test fun `over the cap the nudge gives the percent used`() {
        val s = budgetStatus(monthly(100_000), listOf(debit(1, 107_000, now)), ownVpas, ownNames, now)
        assertEquals("Over your monthly budget" to "You've used 107% of your monthly budget.", budgetNudgeCopy(s))
    }

    @Test fun `a sliver over the cap says over, not used 100 percent`() {
        val s = budgetStatus(monthly(100_000), listOf(debit(1, 100_050, now)), ownVpas, ownNames, now)
        assertEquals("Over your monthly budget" to "You've gone over your monthly budget.", budgetNudgeCopy(s))
    }

    @Test fun `nearing the cap the nudge gives the percent used`() {
        val s = budgetStatus(monthly(100_000), listOf(debit(1, 84_000, now)), ownVpas, ownNames, now)
        assertEquals("Nearing your monthly budget" to "You've used 84% of your monthly budget.", budgetNudgeCopy(s))
    }

    @Test fun `no nudge ever carries a rupee amount — it shows on the lock screen`() {
        val spends = listOf(84_000L, 100_000L, 100_050L, 107_000L, 250_000L)
        for (period in listOf("DAY", "WEEK", "MONTH")) {
            for (spent in spends) {
                val budget = BudgetEntity(period = period, limitPaise = 100_000, updatedAt = now)
                val (title, text) = budgetNudgeCopy(budgetStatus(budget, listOf(debit(1, spent, now)), ownVpas, ownNames, now))
                assertFalse("$period/$spent: $title", title.contains('₹'))
                assertFalse("$period/$spent: $text", text.contains('₹') || text.contains("Rs"))
            }
        }
    }

    @Test fun `BUDGET_PERIODS are day week month in order`() {
        assertEquals(listOf(InsightsPeriod.DAY, InsightsPeriod.WEEK, InsightsPeriod.MONTH), BUDGET_PERIODS)
    }

    private fun debit(n: Int, paise: Long, ts: Long, status: TxnStatus = TxnStatus.CONFIRMED) =
        TransactionEntity(
            id = "t$n", amountPaise = paise, direction = Direction.DEBIT, status = status,
            payeeName = "Merchant $n", timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
        )

    private fun credit(n: Int, paise: Long, ts: Long) =
        TransactionEntity(
            id = "t$n", amountPaise = paise, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
            timestampEvent = ts, timestampCaptured = ts, source = Source.SMS,
        )
}
