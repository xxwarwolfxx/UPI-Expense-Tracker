package com.goushik.upiwallet.widget

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.spendInPeriod

/** The home-screen widgets. SMALL/BIG are spend-only; LARGE is the only balance widget; BUDGET is the
 *  monthly-budget money-stack meter (% left); QUOTA is the week+month percent-SPENT meter. Note the two
 *  meters run in opposite directions on purpose — BUDGET pictures what is still in your pocket, QUOTA
 *  reports what has gone. */
enum class WidgetSize { SMALL, BIG, LARGE, BUDGET, QUOTA }

/** A per-account "at setup" baseline (live per-account attribution is deferred in BalanceCalculator,
 *  so the chips show the entered baseline — matching Home's "at setup" chips, NOT the live total). */
data class WidgetAccount(val label: String, val baselinePaise: Long)

/**
 * One-shot snapshot the widgets render. A PURE builder so it's testable and reused by BOTH the
 * reactive freshness collector (in UpiWalletApp) and the on-demand [WidgetData.load]. The spend
 * numbers come from [spendInPeriod] — the same predicate AND the same bounded day/week/month window as
 * Home ([com.goushik.upiwallet.ui.home.buildHomeState]), Budgets and Insights — so every widget
 * reconciles exactly with the app. `available` is the live combined balance from [BalanceCalculator],
 * meaningful only when [hasBalance].
 */
data class WidgetSnapshot(
    val onboarded: Boolean,
    val availablePaise: Long,
    val accounts: List<WidgetAccount>,
    val dayPaise: Long,
    val dayCount: Int,
    val monthPaise: Long,
    val monthCount: Int,
    val weekPaise: Long,
    val weekCount: Int,
    /** Phase C: false = spend-only → the large widget is PURE SPEND (no balance, no reveal, no chips). */
    val showBalance: Boolean = true,
    /** The monthly cap's limit in paise, or 0 when no MONTH budget is set → the budget widget's empty state.
     *  Spent-so-far against it is [monthPaise], so the meter reconciles with Home + the Budgets screen. */
    val monthBudgetLimitPaise: Long = 0L,
    /** The weekly cap's limit in paise, or 0 when no WEEK budget is set → the quota widget hides that row.
     *  Spent-so-far against it is [weekPaise], so it reconciles with Home + the Budgets screen. */
    val weekBudgetLimitPaise: Long = 0L,
) {
    /** True once at least one account is set up. With none there is no starting balance, so
     *  [availablePaise] is just minus every payment ever made — a large, meaningless negative. The LARGE
     *  widget shows a dash instead (the same "—" Settings shows), never that number. */
    val hasBalance: Boolean get() = accounts.isNotEmpty()

    companion object {
        fun build(
            txns: List<TransactionEntity>,
            anchors: List<BalanceAnchorEntity>,
            profile: UserProfileEntity?,
            budgets: List<BudgetEntity>,
            nowMs: Long,
        ): WidgetSnapshot {
            val ownVpas = profile?.ownVpaSet() ?: emptySet()
            val ownNames = profile?.ownNameSet() ?: emptySet()
            val day = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.DAY, nowMs).toList()
            val week = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.WEEK, nowMs).toList()
            val month = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.MONTH, nowMs).toList()
            return WidgetSnapshot(
                // Spend-only users never set a balance (no anchors), so the gate is onboardedAt alone now.
                onboarded = profile?.onboardedAt != null,
                availablePaise = BalanceCalculator.available(anchors, txns, ownVpas, ownNames),
                accounts = anchors.map { WidgetAccount(it.accountLabel, it.baselinePaise) },
                dayPaise = day.sumOf { it.amountPaise },
                dayCount = day.size,
                monthPaise = month.sumOf { it.amountPaise },
                monthCount = month.size,
                weekPaise = week.sumOf { it.amountPaise },
                weekCount = week.size,
                showBalance = profile?.showBalance ?: true,
                monthBudgetLimitPaise = budgets
                    .firstOrNull { it.period == InsightsPeriod.MONTH.name && it.limitPaise > 0L }
                    ?.limitPaise ?: 0L,
                weekBudgetLimitPaise = budgets
                    .firstOrNull { it.period == InsightsPeriod.WEEK.name && it.limitPaise > 0L }
                    ?.limitPaise ?: 0L,
            )
        }
    }
}

object WidgetData {
    /** One-shot load off the repo (four quick reads). Call from a background coroutine. */
    suspend fun load(repo: TransactionRepository): WidgetSnapshot =
        WidgetSnapshot.build(
            repo.transactions(), repo.anchors(), repo.profile(), repo.budgets(), System.currentTimeMillis(),
        )
}
