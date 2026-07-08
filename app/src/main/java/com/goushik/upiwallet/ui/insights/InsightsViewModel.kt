package com.goushik.upiwallet.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.insights.CategorySlice
import com.goushik.upiwallet.domain.insights.InsightsData
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.MapData
import com.goushik.upiwallet.domain.insights.bucketSpend
import com.goushik.upiwallet.domain.insights.buildMapData
import com.goushik.upiwallet.domain.insights.categoryRollup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class InsightsUiState(
    val loading: Boolean = true,
    val data: InsightsData,
    val map: MapData = MapData.Empty,
    val categories: List<CategorySlice> = emptyList(),
    val period: InsightsPeriod = InsightsPeriod.MONTH,
) {
    companion object {
        /** A harmless, non-null default while Room hasn't emitted yet (matches the contract: data is
         *  never null; a fully-empty range still yields a valid InsightsData with >=0 points). */
        val Loading = InsightsUiState(
            loading = true,
            data = InsightsData(points = emptyList(), totalPaise = 0L, rangeLabel = "", txnCount = 0),
            map = MapData.Empty,
            period = InsightsPeriod.MONTH,
        )
    }
}

/**
 * Insights tab VM. Same shape as [com.goushik.upiwallet.ui.home.HomeViewModel] — a combine over the repo
 * flows folded into one display-ready state — but adds two screen-written control flows ([period],
 * [customRange]) so the chart re-buckets reactively. All spend math lives in the PURE [bucketSpend]
 * (which itself reuses the Home spend filter + BalanceCalculator.isSelfTransfer), keeping this VM thin.
 */
class InsightsViewModel(repo: TransactionRepository) : ViewModel() {

    /** The active window; the segmented filter writes this. */
    val period: MutableStateFlow<InsightsPeriod> = MutableStateFlow(InsightsPeriod.MONTH)

    /** (startMs, endMs) set when the user confirms a Custom DateRangePicker; null otherwise. */
    val customRange: MutableStateFlow<Pair<Long, Long>?> = MutableStateFlow(null)

    val state: StateFlow<InsightsUiState> = combine(
        repo.observeTransactions(),
        repo.observeProfile(),
        period,
        customRange,
    ) { txns, profile, p, range ->
        val now = System.currentTimeMillis()
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()
        val data = bucketSpend(
            txns = txns,
            ownVpas = ownVpas,
            ownNames = ownNames,
            period = p,
            nowMs = now,
            customStartMs = range?.first,
            customEndMs = range?.second,
        )
        // Same txns, window, predicate, and clock as the chart → the map footnote reconciles with it.
        val map = buildMapData(
            txns = txns,
            ownVpas = ownVpas,
            ownNames = ownNames,
            period = p,
            nowMs = now,
            locationEnabled = ServiceLocator.locationSettings.enabled,
            customStartMs = range?.first,
            customEndMs = range?.second,
        )
        // By-category breakdown for the donut — same window/predicate, so the slices sum to `data.totalPaise`.
        val categories = categoryRollup(
            txns = txns, ownVpas = ownVpas, ownNames = ownNames, period = p, nowMs = now,
            customStartMs = range?.first, customEndMs = range?.second,
        )
        InsightsUiState(loading = false, data = data, map = map, categories = categories, period = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState.Loading)

    companion object {
        val Factory = viewModelFactory { initializer { InsightsViewModel(ServiceLocator.repository) } }
    }
}
