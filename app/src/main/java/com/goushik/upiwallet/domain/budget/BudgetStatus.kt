package com.goushik.upiwallet.domain.budget

import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.spendInPeriod
import com.goushik.upiwallet.domain.insights.spendWindow

/** The three cap periods Budgets exposes — a subset of [InsightsPeriod], in display order. */
val BUDGET_PERIODS = listOf(InsightsPeriod.DAY, InsightsPeriod.WEEK, InsightsPeriod.MONTH)

/**
 * PURE — a budget's spent-so-far vs its cap for the CURRENT period. Spend is summed with [spendInPeriod],
 * the same filter Home, the widgets and the Insights chart use, so the bar can never disagree with the
 * totals. Host unit-tested (BudgetStatusTest).
 */
data class BudgetStatus(
    val period: InsightsPeriod,
    val limitPaise: Long,
    val spentPaise: Long,
    val windowStart: Long,
) {
    /** Whole-percent of the cap used (0 when no cap). Can exceed 100. */
    val pct: Int get() = if (limitPaise <= 0L) 0 else ((spentPaise.toDouble() / limitPaise) * 100).toInt()

    /** Signed: positive = headroom left, negative = how far over. */
    val remainingPaise: Long get() = limitPaise - spentPaise

    /** At or past the cap → the over (coral) state + the "over budget" nudge. */
    val isOver: Boolean get() = limitPaise > 0L && spentPaise >= limitPaise

    /** 80%+ but not yet over → the near (amber) state + the "nearing budget" nudge. */
    val isNear: Boolean get() = !isOver && pct >= NEAR_PCT

    /** The highest alert threshold this status has reached: 100 (over) / 80 (near) / 0 (calm). */
    fun reachedThreshold(): Int = when {
        isOver -> 100
        isNear -> NEAR_PCT
        else -> 0
    }

    companion object { const val NEAR_PCT = 80 }
}

/** PURE — build the status for one [budget] from the full txn list + the user's own VPAs/names + clock. */
fun budgetStatus(
    budget: BudgetEntity,
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    nowMs: Long,
): BudgetStatus {
    val period = InsightsPeriod.valueOf(budget.period)
    val spent = spendInPeriod(txns, ownVpas, ownNames, period, nowMs).sumOf { it.amountPaise }
    return BudgetStatus(period, budget.limitPaise, spent, spendWindow(period, nowMs).startMs)
}
