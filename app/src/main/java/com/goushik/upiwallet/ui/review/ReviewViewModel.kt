package com.goushik.upiwallet.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.insights.isSpend
import com.goushik.upiwallet.domain.review.BackedOutDetection
import com.goushik.upiwallet.domain.review.RemovedPayments
import com.goushik.upiwallet.domain.review.SampleRows
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.home.toRowUi
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.prettyName
import com.goushik.upiwallet.util.upiAppName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One "Did this go through?" card — a payment screen the owner most likely backed out of
 * ([BackedOutDetection]). Still counted; Keep leaves it, Remove soft-deletes it (with Undo).
 */
data class BackedOutCard(
    val id: String,
    val amountPaise: Long,
    val who: String,
    val whenLabel: String,
    val app: String,
    val gapSeconds: Long,
) {
    /** The card's sentence after "Did this go through?" — the facts that raised the question. */
    val body: String
        get() = "${Money.format(amountPaise)} to $who, $whenLabel. We never saw it finish, and another " +
            "$app payment was started ${gapSeconds}s later."
}

/** A Remove just made from Review, offered back as Undo until the next action or [ReviewViewModel.UNDO_MS]. */
data class ReviewUndo(val id: String, val previous: TxnStatus, val who: String, val amountPaise: Long)

/** One seeder row as the sample-payments card lists it. */
data class SampleRowUi(
    val id: String,
    val title: String,
    val amountPaise: Long,
    val direction: Direction,
    /** "3 Jun" — the card adds "· sample". */
    val dayLabel: String,
)

/**
 * What the two amber cards at the top of Review need: the developer seeder's rows that still count ("Sample
 * payments found") and the profile's UPI IDs that are the seeder's sample IDs ("Your UPI IDs look like the
 * demo ones"). See [SampleRows]. Each card shows only while its part is non-empty.
 */
data class SampleCleanup(
    /** Seeder rows not yet removed, newest first. */
    val liveIds: List<String> = emptyList(),
    /** What those rows add to spend — the size of the distortion. Same rule as every spend total ([isSpend]),
     *  so the seeder's transfer to its own sample UPI ID is left out while that ID is still in the profile. */
    val liveDebitPaise: Long = 0L,
    /** The made-up income those rows add (their credits). */
    val liveCreditPaise: Long = 0L,
    /** The same rows for the card's list, newest first. */
    val rows: List<SampleRowUi> = emptyList(),
    /** "21 Apr – 3 Jun": the oldest to the newest live seeder row (one date when they share a day). */
    val rangeLabel: String = "",
    /** The profile's UPI IDs that are the seeder's sample IDs. */
    val sampleUpiIds: Set<String> = emptySet(),
    /** How many UPI IDs the profile holds in all, so the card can say "Both" or "1 of the 3". */
    val ownUpiIdCount: Int = 0,
) {
    val count: Int get() = liveIds.size
    val needsAttention: Boolean get() = liveIds.isNotEmpty() || sampleUpiIds.isNotEmpty()

    /** "31 sample payments from demo mode are in your history". */
    val rowsTitle: String
        get() = if (count == 1) "1 sample payment from demo mode is in your history"
        else "$count sample payments from demo mode are in your history"

    /** The demo-ID card's sentence, sized to how many of the profile's IDs are the sample ones. */
    val upiIdsBody: String
        get() {
            val n = sampleUpiIds.size
            val total = maxOf(ownUpiIdCount, n)
            return when {
                n == total && n == 1 -> "The ID saved in your profile came from demo mode, so the app can't spot " +
                    "money you move between your own accounts."
                n == total && n == 2 -> "Both IDs saved in your profile came from demo mode, so the app can't " +
                    "spot money you move between your own accounts."
                n == total -> "All $n IDs saved in your profile came from demo mode, so the app can't spot " +
                    "money you move between your own accounts."
                else -> "$n of the $total IDs saved in your profile came from demo mode, so the app may miss " +
                    "money you move between your own accounts."
            }
        }
}

data class ReviewUiState(
    val loading: Boolean = true,
    /** Payments whose category we weren't sure about (`needsReview`). */
    val items: List<TxnRowUi> = emptyList(),
    /** "Did this go through?" questions, newest first. */
    val backedOut: List<BackedOutCard> = emptyList(),
    val undo: ReviewUndo? = null,
    val samples: SampleCleanup = SampleCleanup(),
) {
    val count: Int get() = items.size + backedOut.size

    /** The bottom nav's Review dot: lit while any question is open (a category, a "Did this go through?"
     *  card) or a sample-data card is showing, so each of them alone still points the owner to Review. */
    val hasOpenQuestions: Boolean get() = count > 0 || samples.needsAttention

    companion object { val Empty = ReviewUiState(loading = true) }
}

/**
 * PURE — the whole Review state from the repository snapshots and one clock, so it's host-testable.
 *
 * @param txns every row, newest first (the repository's order).
 * @param appOf txn id → package of the UPI app whose screen created it.
 * @param kept rows the owner already answered Keep for.
 */
fun buildReviewState(
    txns: List<TransactionEntity>,
    appOf: Map<String, String>,
    profile: UserProfileEntity?,
    kept: Set<String>,
    undo: ReviewUndo?,
    now: Long,
): ReviewUiState {
    val ownVpas = profile?.ownVpaSet() ?: emptySet()
    val ownNames = profile?.ownNameSet() ?: emptySet()
    val backedOut = BackedOutDetection.flag(txns, appOf, now, kept).map { f ->
        BackedOutCard(
            id = f.row.id,
            amountPaise = f.row.amountPaise,
            who = prettyName(f.row.payeeName) ?: f.row.payeeVpa ?: "this payee",
            whenLabel = DateTime.dayTime(f.row.timestampEvent),
            app = upiAppName(f.appPkg),
            gapSeconds = f.gapMs / 1000,
        )
    }
    val liveSamples = SampleRows.find(txns).filter { it.status != TxnStatus.DISCARDED }
    return ReviewUiState(
        loading = false,
        items = txns.filter { it.needsReview && it.status != TxnStatus.DISCARDED }
            .map { it.toRowUi(ownVpas, ownNames, now) },
        backedOut = backedOut,
        undo = undo,
        samples = SampleCleanup(
            liveIds = liveSamples.map { it.id },
            liveDebitPaise = liveSamples.filter { isSpend(it, ownVpas, ownNames) }.sumOf { it.amountPaise },
            liveCreditPaise = liveSamples.filter { it.direction == Direction.CREDIT }.sumOf { it.amountPaise },
            rows = liveSamples.map {
                SampleRowUi(
                    id = it.id,
                    title = prettyName(it.payeeName) ?: it.payeeVpa ?: "Sample payment",
                    amountPaise = it.amountPaise,
                    direction = it.direction,
                    dayLabel = DateTime.day(it.timestampEvent, now),
                )
            },
            rangeLabel = sampleRange(liveSamples, now),
            sampleUpiIds = SampleRows.sampleIdsIn(profile?.ownVpasCsv),
            ownUpiIdCount = ownVpas.size,
        ),
    )
}

/** "21 Apr – 3 Jun" for the seeder rows' span; one date when they share a day; "" when there are none. */
internal fun sampleRange(rows: List<TransactionEntity>, now: Long): String {
    if (rows.isEmpty()) return ""
    val first = DateTime.day(rows.minOf { it.timestampEvent }, now)
    val last = DateTime.day(rows.maxOf { it.timestampEvent }, now)
    return if (first == last) first else "$first – $last"
}

/**
 * Review: the payments we weren't sure about. Two kinds of question —
 *  - **category** (`needsReview`): a list; tapping a row opens the detail, where picking a category clears
 *    the flag and the row drops out reactively. Same row mapping + container as Home and All-transactions.
 *  - **did this go through?** ([BackedOutDetection]): a card per payment screen the owner likely backed
 *    out of, answered here with Keep (remembered in [ReviewKeptStore]) or Remove (soft, with Undo).
 * The bottom-nav dot reads this same state ([ReviewUiState.hasOpenQuestions]; AppShell shares this
 * Activity-scoped ViewModel with the Review tab), so it lights for either kind of question.
 */
class ReviewViewModel(
    private val repo: TransactionRepository,
    private val keptStore: ReviewKeptStore,
) : ViewModel() {

    private val undoState = MutableStateFlow<ReviewUndo?>(null)
    private var undoExpiry: Job? = null

    val state: StateFlow<ReviewUiState> = combine(
        repo.observeTransactions(),
        repo.observeCaptureApps(),
        repo.observeProfile(),
        keptStore.keptIds,
        undoState,
    ) { txns, appOf, profile, kept, undo ->
        buildReviewState(txns, appOf, profile, kept, undo, System.currentTimeMillis())
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState.Empty)

    /** "Keep": it went through — stays counted, and the question isn't asked again. */
    fun keep(id: String) {
        keptStore.keep(id)
    }

    /** "Remove": it didn't go through — soft-delete (reversible), offering Undo for a few seconds. */
    fun remove(card: BackedOutCard) {
        ServiceLocator.appScope.launch {
            val previous = repo.removeForUndo(card.id) ?: return@launch
            showUndo(ReviewUndo(card.id, previous, card.who, card.amountPaise))
        }
    }

    fun undoRemove() {
        val u = undoState.value ?: return
        clearUndo()
        ServiceLocator.appScope.launch {
            repo.restoreRemoved(u.id, RemovedPayments.undoStatus(repo.transactionById(u.id), u.previous))
        }
    }

    /** The sample-payments card's "Remove all": soft-remove every seeder row still counted. Each comes back
     *  individually with Put back on Settings → Removed payments. */
    fun removeSamplePayments() {
        ServiceLocator.appScope.launch {
            // Re-derived from the database, not the on-screen state, so it removes exactly what's there now.
            val ids = SampleRows.find(repo.transactions())
                .filter { it.status != TxnStatus.DISCARDED }
                .map { it.id }
            if (ids.isNotEmpty()) repo.removeAll(ids)
        }
    }

    private fun showUndo(u: ReviewUndo) {
        undoExpiry?.cancel()
        undoState.value = u
        undoExpiry = viewModelScope.launch {
            delay(UNDO_MS)
            if (undoState.value == u) undoState.value = null
        }
    }

    private fun clearUndo() {
        undoExpiry?.cancel()
        undoState.value = null
    }

    companion object {
        /** How long a Review Undo stays offered. */
        const val UNDO_MS = 8_000L

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                ReviewViewModel(ServiceLocator.repository, ReviewKeptStore(app))
            }
        }
    }
}
