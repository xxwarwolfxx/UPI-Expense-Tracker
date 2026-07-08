package com.goushik.upiwallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.AccountMatch
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.Payee
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.domain.budget.budgetStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.prettyName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A per-account starting baseline, shown on the hero chips labeled "at setup". */
data class AccountBalance(val label: String, val baselinePaise: Long)

/** Per-account spend over a window — drives the spend-only hero's "from which account" breakdown. */
data class AccountSpend(val label: String, val spentPaise: Long)

/** A transaction pre-mapped for display — the screen stays dumb and the mapping stays testable. */
data class TxnRowUi(
    val id: String,
    val title: String,
    val amountPaise: Long,
    val direction: Direction,
    val isSelfTransfer: Boolean,
    val needsReview: Boolean,
    val timeLabel: String,
    val category: String? = null,
    /** Best-effort: this payee looks like an individual (P2P) → drives the person-vs-business row glyph. */
    val isPerson: Boolean = false,
)

/** Maps a stored transaction to its display row — the SINGLE definition shared by Home, Review, and the
 *  All-transactions list, so a payment renders identically wherever it appears. Pure (java.time only),
 *  so it stays unit-testable off-device. */
fun TransactionEntity.toRowUi(ownVpas: Set<String>, ownNames: Set<String>, now: Long): TxnRowUi =
    TxnRowUi(
        id = id,
        title = prettyName(payeeName) ?: payeeVpa ?: bankLabel ?: "Payment",
        amountPaise = amountPaise,
        direction = direction,
        isSelfTransfer = BalanceCalculator.isSelfTransfer(this, ownVpas, ownNames),
        needsReview = needsReview,
        timeLabel = DateTime.rowTime(timestampEvent, now),
        category = category,
        isPerson = Payee.isPerson(payeeName, payeeVpa),
    )

data class HomeUiState(
    val loading: Boolean = true,
    val availableBalancePaise: Long = 0L,
    val accounts: List<AccountBalance> = emptyList(),
    val accountMonthSpend: List<AccountSpend> = emptyList(),
    val monthSpentPaise: Long = 0L,
    val monthCount: Int = 0,
    val weekSpentPaise: Long = 0L,
    val weekCount: Int = 0,
    val todaySpentPaise: Long = 0L,
    val todayCount: Int = 0,
    val recentTxns: List<TxnRowUi> = emptyList(),
    val displayName: String = "",
    /** Phase C wallet mode: true = balance hero; false = spend-only hero (balance behind a peek-eye). */
    val showBalance: Boolean = true,
    /** The MONTH cap's live status, or null when no monthly budget is set — drives the on-card budget bar. */
    val monthBudget: BudgetStatus? = null,
) {
    companion object {
        val Empty = HomeUiState(loading = true)
    }
}

/**
 * First ViewModel in the app — establishes the pattern. Combines the three repository flows into one
 * display-ready [HomeUiState]. Capture-health is intentionally NOT here: it's system-settings state
 * read at the composable layer via `rememberCaptureGrants()`. Self-transfers are kept in the list
 * (marked neutral) but excluded from balance + spend totals, matching [BalanceCalculator].
 */
class HomeViewModel(repo: TransactionRepository) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        repo.observeTransactions(), // already DESC by timestampEvent
        repo.observeAnchors(),
        repo.observeProfile(),
        repo.observeBudgets(),
    ) { txns, anchors, profile, budgets ->
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()
        val now = System.currentTimeMillis()
        val monthStart = DateTime.startOfMonthMs(now)
        val weekStart = DateTime.startOfWeekMs(now)
        val dayStart = DateTime.startOfDayMs(now)

        fun spendSince(fromMs: Long) = txns.asSequence().filter {
            it.status != TxnStatus.DISCARDED &&
                it.direction == Direction.DEBIT &&
                !BalanceCalculator.isSelfTransfer(it, ownVpas, ownNames) &&
                it.timestampEvent >= fromMs
        }

        val visible = txns.filter { it.status != TxnStatus.DISCARDED }

        // The monthly cap (if set) — same isSpend/spendWindow the tiles use, so spent == monthSpentPaise.
        val monthBudget = budgets
            .firstOrNull { it.period == InsightsPeriod.MONTH.name && it.limitPaise > 0L }
            ?.let { budgetStatus(it, txns, ownVpas, ownNames, now) }

        HomeUiState(
            loading = false,
            availableBalancePaise = BalanceCalculator.available(anchors, txns, ownVpas, ownNames),
            accounts = anchors.map { AccountBalance(it.accountLabel, it.baselinePaise) },
            // Per-account spend this month, attributed by bank label (canonicalised) → only the user's own
            // set-up accounts. Spends without a matching account simply aren't shown per-account.
            accountMonthSpend = anchors.map { a ->
                AccountSpend(
                    a.accountLabel,
                    spendSince(monthStart).filter { AccountMatch.matches(it.bankLabel, a.accountLabel) }
                        .sumOf { it.amountPaise },
                )
            },
            monthSpentPaise = spendSince(monthStart).sumOf { it.amountPaise },
            monthCount = spendSince(monthStart).count(),
            weekSpentPaise = spendSince(weekStart).sumOf { it.amountPaise },
            weekCount = spendSince(weekStart).count(),
            todaySpentPaise = spendSince(dayStart).sumOf { it.amountPaise },
            todayCount = spendSince(dayStart).count(),
            recentTxns = visible.take(5).map { it.toRowUi(ownVpas, ownNames, now) },
            displayName = profile?.displayName.orEmpty(),
            showBalance = profile?.showBalance ?: true,
            monthBudget = monthBudget,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Empty)

    companion object {
        val Factory = viewModelFactory { initializer { HomeViewModel(ServiceLocator.repository) } }
    }
}
