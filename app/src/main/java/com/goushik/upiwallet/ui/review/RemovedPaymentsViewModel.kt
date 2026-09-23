package com.goushik.upiwallet.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.review.RemovedPayments
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.home.toRowUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One removed payment: its usual row, plus whether the bank itself confirmed it (worth a second look). */
data class RemovedRowUi(val row: TxnRowUi, val bankConfirmed: Boolean)

data class RemovedPaymentsUiState(
    val loading: Boolean = true,
    /** Newest first. */
    val rows: List<RemovedRowUi> = emptyList(),
) {
    val bankConfirmedCount: Int get() = rows.count { it.bankConfirmed }
}

/** PURE — [removed] (newest first, all DISCARDED) mapped to display rows, host-testable. */
fun buildRemovedState(removed: List<TransactionEntity>, profile: UserProfileEntity?, now: Long): RemovedPaymentsUiState {
    val ownVpas = profile?.ownVpaSet() ?: emptySet()
    val ownNames = profile?.ownNameSet() ?: emptySet()
    return RemovedPaymentsUiState(
        loading = false,
        rows = removed.map { RemovedRowUi(it.toRowUi(ownVpas, ownNames, now), RemovedPayments.isBankConfirmed(it)) },
    )
}

/**
 * Logic for a "Removed payments" screen (the screen itself waits for the owner's mockup red-pen). Today a
 * removed payment is out of every list, so a mis-tap — or an old capture bug that removed a payment the
 * bank had confirmed — can't be seen or undone in the app. This lists them, newest first, marks the
 * bank-confirmed ones, and puts any of them back ([RemovedPayments.putBackStatus] decides the status).
 */
class RemovedPaymentsViewModel(private val repo: TransactionRepository) : ViewModel() {

    val state: StateFlow<RemovedPaymentsUiState> = combine(
        repo.observeRemovedTransactions(),
        repo.observeProfile(),
    ) { removed, profile ->
        buildRemovedState(removed, profile, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemovedPaymentsUiState())

    /** Put a removed payment back into every total. Process-scoped so it completes even if the screen closes. */
    fun putBack(id: String) {
        ServiceLocator.appScope.launch {
            val t = repo.transactionById(id) ?: return@launch
            repo.restoreRemoved(id, RemovedPayments.putBackStatus(t))
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RemovedPaymentsViewModel(ServiceLocator.repository) }
        }
    }
}
