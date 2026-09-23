package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus

/**
 * The rules for bringing a removed (DISCARDED) payment back. Removing is a soft delete — the row and its
 * raw capture stay — so every removal must be undoable, and the status it comes back with must be honest.
 * Pure + unit-tested; the DAO's guarded restore applies the result.
 *
 * Two ways back:
 *  - **Undo** (right after a Remove): the row returns to exactly the status it had a moment ago.
 *  - **Put back** (later, from the removed list): we no longer know what it was, so we derive it. The bank's
 *    proof (an RRN) always means CONFIRMED; a hand-entered payment was CONFIRMED on save, so it returns
 *    CONFIRMED too — anything else would quietly downgrade it; a screen-only capture returns UNCONFIRMED,
 *    the most we can honestly claim without the bank's word.
 */
object RemovedPayments {

    /** A removed row the bank itself confirmed — its SMS merged (or created) the row and left its RRN.
     *  These are the ones most worth a second look: the money certainly moved. */
    fun isBankConfirmed(t: TransactionEntity): Boolean =
        t.rrn != null && t.source.contains(Source.SMS)

    /** The status "Put back" restores [t] to. */
    fun putBackStatus(t: TransactionEntity): TxnStatus = when {
        t.rrn != null -> TxnStatus.CONFIRMED
        t.source == Source.MANUAL -> TxnStatus.CONFIRMED
        else -> TxnStatus.UNCONFIRMED
    }

    /** The status "Undo" restores to: what the row was before the Remove — unless the bank has proved it
     *  since (an RRN), which always wins. [previous] is never DISCARDED (there'd be nothing to undo). */
    fun undoStatus(t: TransactionEntity?, previous: TxnStatus): TxnStatus = when {
        t?.rrn != null -> TxnStatus.CONFIRMED
        previous == TxnStatus.DISCARDED -> TxnStatus.UNCONFIRMED
        else -> previous
    }
}
