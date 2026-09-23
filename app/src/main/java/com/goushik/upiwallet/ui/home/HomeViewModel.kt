package com.goushik.upiwallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.AccountMatch
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.Payee
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.domain.budget.budgetStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.spendInPeriod
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
 * PURE — the whole Home state from the four repo snapshots + one clock, so it is host-testable (the
 * totals-reconcile test pins it against the widgets, Budgets and Insights). Every spend total is
 * [spendInPeriod]: the same predicate AND the same bounded window those surfaces use, so "this month"
 * is one number app-wide — a row dated in the future is left out here exactly as Budgets leaves it out.
 */
fun buildHomeState(
    txns: List<TransactionEntity>,          // DESC by timestampEvent (the repo's order)
    anchors: List<BalanceAnchorEntity>,
    profile: UserProfileEntity?,
    budgets: List<BudgetEntity>,
    now: Long,
): HomeUiState {
    val ownVpas = profile?.ownVpaSet() ?: emptySet()
    val ownNames = profile?.ownNameSet() ?: emptySet()
    val month = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.MONTH, now).toList()
    val week = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.WEEK, now).toList()
    val today = spendInPeriod(txns, ownVpas, ownNames, InsightsPeriod.DAY, now).toList()

    val visible = txns.filter { it.status != TxnStatus.DISCARDED }

    // The monthly cap (if set) — built from the same spendInPeriod, so spent == monthSpentPaise.
    val monthBudget = budgets
        .firstOrNull { it.period == InsightsPeriod.MONTH.name && it.limitPaise > 0L }
        ?.let { budgetStatus(it, txns, ownVpas, ownNames, now) }

    return HomeUiState(
        loading = false,
        availableBalancePaise = BalanceCalculator.available(anchors, txns, ownVpas, ownNames),
        accounts = anchors.map { AccountBalance(it.accountLabel, it.baselinePaise) },
        // Per-account spend this month, attributed by bank label (canonicalised) → only the user's own
        // set-up accounts. Spends without a matching account simply aren't shown per-account.
        accountMonthSpend = anchors.map { a ->
            AccountSpend(
                a.accountLabel,
                month.filter { AccountMatch.matches(it.bankLabel, a.accountLabel) }.sumOf { it.amountPaise },
            )
        },
        monthSpentPaise = month.sumOf { it.amountPaise },
        monthCount = month.size,
        weekSpentPaise = week.sumOf { it.amountPaise },
        weekCount = week.size,
        todaySpentPaise = today.sumOf { it.amountPaise },
        todayCount = today.size,
        recentTxns = visible.take(5).map { it.toRowUi(ownVpas, ownNames, now) },
        displayName = profile?.displayName.orEmpty(),
        showBalance = profile?.showBalance ?: true,
        monthBudget = monthBudget,
    )
}

/**
 * First ViewModel in the app — establishes the pattern. Combines the repository flows into one
 * display-ready [HomeUiState] via the pure [buildHomeState]. Capture-health is intentionally NOT here:
 * it's system-settings state read at the composable layer via `rememberCaptureGrants()`. Self-transfers
 * are kept in the list (marked neutral) but excluded from balance + spend totals, matching
 * [BalanceCalculator].
 */
class HomeViewModel(repo: TransactionRepository) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        repo.observeTransactions(), // already DESC by timestampEvent
        repo.observeAnchors(),
        repo.observeProfile(),
        repo.observeBudgets(),
    ) { txns, anchors, profile, budgets ->
        buildHomeState(txns, anchors, profile, budgets, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Empty)

    companion object {
        val Factory = viewModelFactory { initializer { HomeViewModel(ServiceLocator.repository) } }
    }
}
