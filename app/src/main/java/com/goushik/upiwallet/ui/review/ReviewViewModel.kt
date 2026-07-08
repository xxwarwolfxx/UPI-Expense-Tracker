package com.goushik.upiwallet.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.home.toRowUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ReviewUiState(
    val loading: Boolean = true,
    val items: List<TxnRowUi> = emptyList(),
) {
    companion object { val Empty = ReviewUiState(loading = true) }
}

/**
 * Review is a simple **list** of the payments we weren't sure about (the `needsReview` rows) — the same
 * shared row mapping + container as Home and All-transactions, so a payment renders identically everywhere.
 * Tapping a row opens the full transaction detail, where the category is picked (which clears the flag) and
 * any duplicate twin is resolved; the row then drops from this list reactively. Resolving lives in detail,
 * so this VM is read-only — it just maps `observeReviewTransactions()` to rows and keeps the bottom-nav
 * Review badge in lockstep (both read the same `needsReview` set).
 */
class ReviewViewModel(repo: TransactionRepository) : ViewModel() {

    val state: StateFlow<ReviewUiState> = combine(
        repo.observeReviewTransactions(),
        repo.observeProfile(),
    ) { reviewTxns, profile ->
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()
        val now = System.currentTimeMillis()
        ReviewUiState(
            loading = false,
            items = reviewTxns.map { it.toRowUi(ownVpas, ownNames, now) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState.Empty)

    companion object {
        val Factory = viewModelFactory {
            initializer { ReviewViewModel(ServiceLocator.repository) }
        }
    }
}
