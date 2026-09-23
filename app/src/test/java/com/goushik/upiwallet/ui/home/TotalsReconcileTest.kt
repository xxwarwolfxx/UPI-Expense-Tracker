package com.goushik.upiwallet.ui.home

import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.budget.budgetStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.bucketSpend
import com.goushik.upiwallet.domain.insights.categoryRollup
import com.goushik.upiwallet.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The totals rule, pinned: for the same window, Home = the widgets = Budgets = Insights (chart AND
 * donut). Each surface builds its number through its own pure entry point ([buildHomeState],
 * [WidgetSnapshot.build], [budgetStatus], [bucketSpend], [categoryRollup]); if any of them drifts to a
 * different predicate or window this test names which. The ledger deliberately carries every row kind
 * that has ever made two screens disagree: a future-dated manual row, a self-transfer, a removed row, a
 * credit, and last month's spend.
 */
class TotalsReconcileTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(m: Int, d: Int, h: Int) = ZonedDateTime.of(2026, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val now = at(9, 23, 15)   // Wednesday 23 Sep 2026, 3 PM
    private val profile = UserProfileEntity(
        displayName = "Ramesh Kumar", ownVpasCsv = "ramesh@okaxis", onboardedAt = 1L, showBalance = false,
    )
    private val budgets = listOf(
        BudgetEntity(period = "DAY", limitPaise = 100_000, updatedAt = now),
        BudgetEntity(period = "WEEK", limitPaise = 500_000, updatedAt = now),
        BudgetEntity(period = "MONTH", limitPaise = 2_500_000, updatedAt = now),
    )

    private val ledger = listOf(
        debit("today", 12_000, at(9, 23, 9), category = "Food"),
        debit("monday", 30_000, at(9, 21, 20), category = "Groceries"),
        debit("earlier-this-month", 150_000, at(9, 2, 11), category = "Food"),
        debit("last-month", 90_000, at(8, 30, 18), category = "Food"),
        debit("tomorrow", 40_000, at(9, 24, 10), category = "Food"),            // future-dated manual row
        debit("next-week", 60_000, at(9, 29, 10), category = "Bills & Utilities"),
        debit("next-month", 75_000, at(10, 3, 10)),
        debit("to-self", 500_000, at(9, 22, 10), payeeName = "Ramesh Kumar"),   // own name → not spend
        debit("removed", 99_000, at(9, 23, 10), status = TxnStatus.DISCARDED),
        TransactionEntity(
            id = "salary", amountPaise = 5_000_000, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
            timestampEvent = at(9, 1, 9), timestampCaptured = at(9, 1, 9), source = Source.SMS,
        ),
    ).sortedByDescending { it.timestampEvent }

    private val ownVpas = profile.ownVpaSet()
    private val ownNames = profile.ownNameSet()

    @Test fun `today, this week and this month agree on every surface`() {
        val home = buildHomeState(ledger, emptyList(), profile, budgets, now)
        val widget = WidgetSnapshot.build(ledger, emptyList(), profile, budgets, now)

        check(InsightsPeriod.DAY, home.todaySpentPaise, home.todayCount, widget.dayPaise, widget.dayCount, 12_000L)
        // Tomorrow's row IS inside this week and this month on every surface alike — agreement, not a
        // special case (the Add picker now stops new future dates from being entered at all).
        check(InsightsPeriod.WEEK, home.weekSpentPaise, home.weekCount, widget.weekPaise, widget.weekCount, 82_000L)
        check(InsightsPeriod.MONTH, home.monthSpentPaise, home.monthCount, widget.monthPaise, widget.monthCount, 292_000L)
    }

    @Test fun `the budget line on the Home card matches the hero number above it`() {
        val home = buildHomeState(ledger, emptyList(), profile, budgets, now)
        assertEquals(home.monthSpentPaise, home.monthBudget!!.spentPaise)
    }

    private fun check(
        period: InsightsPeriod,
        homePaise: Long, homeCount: Int,
        widgetPaise: Long, widgetCount: Int,
        expected: Long,
    ) {
        val chart = bucketSpend(ledger, ownVpas, ownNames, period, now)
        val donut = categoryRollup(ledger, ownVpas, ownNames, period, now)
        val budget = budgetStatus(budgets.first { it.period == period.name }, ledger, ownVpas, ownNames, now)
        assertEquals("$period: Home", expected, homePaise)
        assertEquals("$period: widget", expected, widgetPaise)
        assertEquals("$period: Budgets", expected, budget.spentPaise)
        assertEquals("$period: Insights chart", expected, chart.totalPaise)
        assertEquals("$period: Insights donut", expected, donut.sumOf { it.spentPaise })
        assertEquals("$period: counts", chart.txnCount, homeCount)
        assertEquals("$period: counts", chart.txnCount, widgetCount)
        assertEquals("$period: counts", chart.txnCount, donut.sumOf { it.count })
    }

    private fun debit(
        id: String, paise: Long, ts: Long,
        category: String? = null,
        payeeName: String = "ACME Stores",
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = Direction.DEBIT, status = status,
        payeeName = payeeName, category = category,
        timestampEvent = ts, timestampCaptured = ts, source = Source.MANUAL,
    )
}
