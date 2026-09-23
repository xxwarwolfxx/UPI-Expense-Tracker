package com.goushik.upiwallet.ui.alltxns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.PickerDate
import com.goushik.upiwallet.domain.insights.spendWindow
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.home.toRowUi
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.prettyName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.format.DateTimeFormatter

/** Direction filter for the All-transactions list. SPENT/RECEIVED exclude self-transfers (they're neither
 *  a spend nor income), matching the wallet's balance-netting definition. */
enum class DirFilter(val label: String) { ALL("All types"), SPENT("Spent"), RECEIVED("Received") }

/** A contiguous run of rows sharing a date-section header ("Today" / "Yesterday" / "12 May"). */
data class TxnSection(val header: String, val rows: List<TxnRowUi>)

/** The active filter set. `period == null` is "anytime"; `categoryLabel`/`account` null is "all". `query`
 *  is matched case-insensitively against the displayed name, raw payee, VPA, bank, and category. */
data class AllTxnsFilters(
    val query: String = "",
    val direction: DirFilter = DirFilter.ALL,
    val categoryLabel: String? = null,   // Category.label; null = all categories
    val account: String? = null,         // bankLabel; null = all accounts
    val period: InsightsPeriod? = null,  // null = all time
    val customStartMs: Long? = null,
    val customEndMs: Long? = null,
) {
    /** Non-search filters narrowing the list — drives the "Clear" affordance + the "N of M" count. */
    val activeCount: Int
        get() = listOf(
            direction != DirFilter.ALL, categoryLabel != null, account != null, period != null,
        ).count { it }

    val anyActive: Boolean get() = activeCount > 0 || query.isNotBlank()
}

data class AllTxnsUiState(
    val loading: Boolean = true,
    val sections: List<TxnSection> = emptyList(),
    val resultCount: Int = 0,
    val totalCount: Int = 0,
    val accounts: List<String> = emptyList(),   // distinct bank labels present (for the Account filter)
    val filters: AllTxnsFilters = AllTxnsFilters(),
)

/**
 * PURE — the filters a donut slice opens the list with: that category's SPEND inside the window Insights
 * summed the slice over, so the rows add up to the slice (all-time rows, or credits, would not). A CUSTOM
 * period without both dates is what Insights draws as this month, so it opens as this month.
 */
fun categoryDrillFilters(
    categoryLabel: String,
    period: InsightsPeriod,
    customRange: Pair<Long, Long>?,
): AllTxnsFilters =
    if (period == InsightsPeriod.CUSTOM && customRange == null) {
        AllTxnsFilters(direction = DirFilter.SPENT, categoryLabel = categoryLabel, period = InsightsPeriod.MONTH)
    } else {
        AllTxnsFilters(
            direction = DirFilter.SPENT,
            categoryLabel = categoryLabel,
            period = period,
            customStartMs = customRange?.first.takeIf { period == InsightsPeriod.CUSTOM },
            customEndMs = customRange?.second.takeIf { period == InsightsPeriod.CUSTOM },
        )
    }

private val RANGE_FMT = DateTimeFormatter.ofPattern("d MMM")

/** PURE — the "When" pill for a custom range, e.g. "10 Sep – 15 Sep". The bounds are date-picker values
 *  (00:00 UTC of the picked day), so they are read through [PickerDate] — the device zone would print
 *  the day before anywhere west of UTC. */
fun customRangeLabel(startMs: Long?, endMs: Long?): String {
    if (startMs == null || endMs == null) return "Custom"
    val lo = PickerDate.toLocalDate(minOf(startMs, endMs)).format(RANGE_FMT)
    val hi = PickerDate.toLocalDate(maxOf(startMs, endMs)).format(RANGE_FMT)
    return "$lo – $hi"
}

/**
 * PURE filter + group for the All-transactions list. No Android beyond java.time + the entity, so it's
 * fully unit-testable off-device. Reuses the SAME building blocks as the rest of the app —
 * [BalanceCalculator.isSelfTransfer] for self-transfer detection, [spendWindow] for the period window
 * (so a "This month" filter matches the Insights/Home definition), [toRowUi] for the display mapping, and
 * [DateTime.sectionLabel] for the day headers. Output is newest-first (sections and rows both).
 */
fun buildAllTxns(
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    filters: AllTxnsFilters,
    now: Long,
): AllTxnsUiState {
    val visible = txns.filter { it.status != TxnStatus.DISCARDED }
        .sortedByDescending { it.timestampEvent }
    val accounts = visible.mapNotNull { it.bankLabel }.distinct().sorted()
    val window = filters.period?.let { spendWindow(it, now, filters.customStartMs, filters.customEndMs) }
    val q = filters.query.trim().lowercase()

    val filtered = visible.filter { t ->
        val isSelf = BalanceCalculator.isSelfTransfer(t, ownVpas, ownNames)
        val dirOk = when (filters.direction) {
            DirFilter.ALL -> true
            DirFilter.SPENT -> t.direction == Direction.DEBIT && !isSelf
            DirFilter.RECEIVED -> t.direction == Direction.CREDIT && !isSelf
        }
        val catOk = filters.categoryLabel?.let { t.category == it } ?: true
        val accOk = filters.account?.let { t.bankLabel == it } ?: true
        val periodOk = window?.contains(t.timestampEvent) ?: true
        val queryOk = q.isEmpty() || sequenceOf(
            prettyName(t.payeeName), t.payeeName, t.payeeVpa, t.bankLabel, t.category,
        ).filterNotNull().any { it.lowercase().contains(q) }
        dirOk && catOk && accOk && periodOk && queryOk
    }

    // groupBy preserves first-encounter order in a LinkedHashMap, and the source is sorted DESC, so the
    // sections (and the rows inside each) come out newest-first.
    val sections = filtered
        .groupBy { DateTime.sectionLabel(it.timestampEvent, now) }
        .map { (header, rows) -> TxnSection(header, rows.map { it.toRowUi(ownVpas, ownNames, now) }) }

    return AllTxnsUiState(
        loading = false,
        sections = sections,
        resultCount = filtered.size,
        totalCount = visible.size,
        accounts = accounts,
        filters = filters,
    )
}

/**
 * Backs the full-screen "All transactions" view reached from Home's "View all →" and the Insights donut.
 * Combines the live transaction + profile flows with a [MutableStateFlow] of the active filters and
 * delegates the work to the pure [buildAllTxns]. Filter setters just mutate the filter flow; the list
 * recomputes reactively.
 *
 * It is Activity-scoped (AppShell has no nav graph), so its filters outlive the screen. That is right
 * while the list is open (opening a payment and coming back keeps the search), but every fresh OPEN must
 * [startFrom] the caller's filters — otherwise a search from last time keeps narrowing the list behind an
 * empty search box.
 */
class AllTransactionsViewModel(repo: TransactionRepository) : ViewModel() {

    private val filters = MutableStateFlow(AllTxnsFilters())

    val state: StateFlow<AllTxnsUiState> = combine(
        repo.observeTransactions(),  // already DESC by timestampEvent
        repo.observeProfile(),
        filters,
    ) { txns, profile, f ->
        buildAllTxns(
            txns = txns,
            ownVpas = profile?.ownVpaSet() ?: emptySet(),
            ownNames = profile?.ownNameSet() ?: emptySet(),
            filters = f,
            now = System.currentTimeMillis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllTxnsUiState())

    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setDirection(d: DirFilter) = filters.update { it.copy(direction = d) }
    fun setCategory(label: String?) = filters.update { it.copy(categoryLabel = label) }
    fun setAccount(a: String?) = filters.update { it.copy(account = a) }
    fun setPeriod(p: InsightsPeriod?, startMs: Long? = null, endMs: Long? = null) =
        filters.update { it.copy(period = p, customStartMs = startMs, customEndMs = endMs) }

    /** Reset every filter, including the search text (the field is lifted to the screen and cleared too). */
    fun clearFilters() = filters.update { AllTxnsFilters() }

    /** A fresh open of the list: drop whatever the last visit left and start from [entry] (blank from
     *  Home, or a donut slice's [categoryDrillFilters]). Called by the opener, not on recomposition. */
    fun startFrom(entry: AllTxnsFilters) = filters.update { entry }

    /** The filters in force right now — the screen seeds its search box and date pickers from these, so
     *  what it shows always matches what the list is filtered by. */
    val current: AllTxnsFilters get() = filters.value

    companion object {
        val Factory = viewModelFactory { initializer { AllTransactionsViewModel(ServiceLocator.repository) } }
    }
}
