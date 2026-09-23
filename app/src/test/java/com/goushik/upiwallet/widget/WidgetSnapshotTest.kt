package com.goushik.upiwallet.widget

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.budget.budgetStatus
import com.goushik.upiwallet.util.DateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [WidgetSnapshot.build] — specifically that the QUOTA widget's two caps are picked
 * up independently, and that the spend it measures them against is the SAME week/month total the Home
 * tiles show — a widget that disagrees with the Home screen is worse than no widget at all.
 */
class WidgetSnapshotTest {

    private val now = 1_718_452_800_000L // ~2024-06-15, mid-month, a Saturday
    private val profile = UserProfileEntity(
        displayName = "Bram", ownVpasCsv = "bram@oksbi", onboardedAt = 1L, showBalance = false,
    )

    private fun budget(period: String, limit: Long) =
        BudgetEntity(period = period, limitPaise = limit, updatedAt = now)

    private fun debit(n: Int, paise: Long, ts: Long) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
        payeeName = "Merchant $n", timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )

    private fun build(txns: List<TransactionEntity>, budgets: List<BudgetEntity>) =
        WidgetSnapshot.build(txns, emptyList(), profile, budgets, now)

    @Test fun `both caps are picked up independently`() {
        val snap = build(emptyList(), listOf(budget("WEEK", 700_000), budget("MONTH", 8_000_000)))
        assertEquals(700_000L, snap.weekBudgetLimitPaise)
        assertEquals(8_000_000L, snap.monthBudgetLimitPaise)
    }

    @Test fun `a week cap alone leaves the month cap at zero`() {
        val snap = build(emptyList(), listOf(budget("WEEK", 700_000)))
        assertEquals(700_000L, snap.weekBudgetLimitPaise)
        assertEquals(0L, snap.monthBudgetLimitPaise)   // → the quota widget hides the month row
    }

    @Test fun `no budgets at all leaves both at zero`() {
        val snap = build(emptyList(), emptyList())
        assertEquals(0L, snap.weekBudgetLimitPaise)
        assertEquals(0L, snap.monthBudgetLimitPaise)   // → the quota widget's empty state
    }

    @Test fun `a zero-limit row is treated as unset`() {
        val snap = build(emptyList(), listOf(budget("WEEK", 0L)))
        assertEquals(0L, snap.weekBudgetLimitPaise)
    }

    @Test fun `week spend is measured from Monday, month spend from the 1st`() {
        val weekStart = DateTime.startOfWeekMs(now)
        val monthStart = DateTime.startOfMonthMs(now)
        val txns = listOf(
            debit(1, 10_000, now),                    // this week AND this month
            debit(2, 20_000, weekStart - 1),          // before Monday: month only
            debit(3, 30_000, monthStart - 1),         // before the 1st: neither
        )
        val snap = build(txns, listOf(budget("WEEK", 700_000), budget("MONTH", 8_000_000)))
        assertEquals(10_000L, snap.weekPaise)
        assertEquals(30_000L, snap.monthPaise)
        assertEquals(1, snap.weekCount)
        assertEquals(2, snap.monthCount)
    }

    @Test fun `self-transfers and credits never count toward a quota`() {
        val selfPay = TransactionEntity(
            id = "s1", amountPaise = 50_000, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
            payeeName = "Bram", timestampEvent = now, timestampCaptured = now, source = Source.A11Y,
        )
        val credit = TransactionEntity(
            id = "c1", amountPaise = 90_000, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
            timestampEvent = now, timestampCaptured = now, source = Source.SMS,
        )
        val snap = build(listOf(selfPay, credit, debit(1, 10_000, now)), listOf(budget("WEEK", 700_000)))
        assertEquals(10_000L, snap.weekPaise)
    }

    // ── bounded windows: a future-dated row counts nowhere Budgets doesn't count it ──

    @Test fun `a row dated 10 days ahead is left out of today and this week, exactly as Budgets leaves it out`() {
        val day = 86_400_000L
        val txns = listOf(
            debit(1, 10_000, now),
            debit(2, 200_000, now + 10 * day),   // 25 Jun: later this month, but not today or this week
            debit(3, 400_000, now + 20 * day),   // 5 Jul: not even this month
        )
        val budgets = listOf(budget("DAY", 50_000), budget("WEEK", 700_000), budget("MONTH", 8_000_000))
        val snap = build(txns, budgets)

        assertEquals(10_000L, snap.dayPaise)
        assertEquals(1, snap.dayCount)
        assertEquals(10_000L, snap.weekPaise)
        assertEquals(1, snap.weekCount)
        assertEquals(210_000L, snap.monthPaise)                      // 25 Jun is inside [1 Jun, 1 Jul)
        assertEquals(2, snap.monthCount)

        val ownVpas = profile.ownVpaSet()
        val ownNames = profile.ownNameSet()
        fun budgetSpent(period: String) =
            budgetStatus(budgets.first { it.period == period }, txns, ownVpas, ownNames, now).spentPaise
        assertEquals(budgetSpent("DAY"), snap.dayPaise)
        assertEquals(budgetSpent("WEEK"), snap.weekPaise)
        assertEquals(budgetSpent("MONTH"), snap.monthPaise)
    }

    // ── balance with no account ──

    @Test fun `with no account there is no balance to show, only a meaningless negative`() {
        val balanceMode = profile.copy(showBalance = true)
        val snap = WidgetSnapshot.build(listOf(debit(1, 10_000, now)), emptyList(), balanceMode, emptyList(), now)
        assertTrue(snap.showBalance)
        assertFalse(snap.hasBalance)                  // → the LARGE widget shows "—", not the number below
        assertEquals(-10_000L, snap.availablePaise)
    }

    @Test fun `one account is enough to show the balance`() {
        val anchor = BalanceAnchorEntity(id = "a1", accountLabel = "HDFC", baselinePaise = 100_000, anchoredAt = 0L)
        val snap = WidgetSnapshot.build(
            listOf(debit(1, 10_000, now)), listOf(anchor), profile.copy(showBalance = true), emptyList(), now,
        )
        assertTrue(snap.hasBalance)
        assertEquals(90_000L, snap.availablePaise)
    }
}
