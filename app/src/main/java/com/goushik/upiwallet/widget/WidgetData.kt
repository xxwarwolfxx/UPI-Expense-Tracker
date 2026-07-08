package com.goushik.upiwallet.widget

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.isSpend
import com.goushik.upiwallet.util.DateTime

/** The home-screen widgets. SMALL/BIG are spend-only; LARGE is the only balance widget; BUDGET is the
 *  monthly-budget money-stack meter (% left). */
enum class WidgetSize { SMALL, BIG, LARGE, BUDGET }

/** A per-account "at setup" baseline (live per-account attribution is deferred in BalanceCalculator,
 *  so the chips show the entered baseline — matching Home's "at setup" chips, NOT the live total). */
data class WidgetAccount(val label: String, val baselinePaise: Long)

/**
 * One-shot snapshot the widgets render. A PURE builder so it's testable and reused by BOTH the
 * reactive freshness collector (in UpiWalletApp) and the on-demand [WidgetData.load]. The spend
 * numbers use the SAME predicate + window as [com.goushik.upiwallet.ui.home.HomeViewModel] (shared
 * [isSpend] + [DateTime.startOfMonthMs]/[DateTime.startOfWeekMs]), so the widget reconciles exactly
 * with the Home tiles. `available` is the live combined balance from [BalanceCalculator].
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
) {
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
            val dayStart = DateTime.startOfDayMs(nowMs)
            val monthStart = DateTime.startOfMonthMs(nowMs)
            val weekStart = DateTime.startOfWeekMs(nowMs)
            fun spend(fromMs: Long) =
                txns.asSequence().filter { isSpend(it, ownVpas, ownNames) && it.timestampEvent >= fromMs }
            return WidgetSnapshot(
                // Spend-only users never set a balance (no anchors), so the gate is onboardedAt alone now.
                onboarded = profile?.onboardedAt != null,
                availablePaise = BalanceCalculator.available(anchors, txns, ownVpas, ownNames),
                accounts = anchors.map { WidgetAccount(it.accountLabel, it.baselinePaise) },
                dayPaise = spend(dayStart).sumOf { it.amountPaise },
                dayCount = spend(dayStart).count(),
                monthPaise = spend(monthStart).sumOf { it.amountPaise },
                monthCount = spend(monthStart).count(),
                weekPaise = spend(weekStart).sumOf { it.amountPaise },
                weekCount = spend(weekStart).count(),
                showBalance = profile?.showBalance ?: true,
                monthBudgetLimitPaise = budgets
                    .firstOrNull { it.period == InsightsPeriod.MONTH.name && it.limitPaise > 0L }
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
