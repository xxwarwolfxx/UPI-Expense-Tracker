package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import kotlin.math.abs

/**
 * Spots a likely duplicate the [com.goushik.upiwallet.domain.Reconciler] couldn't merge: a *separate*
 * live row with the same amount + direction captured within [windowMs] of [target] — and, when we can
 * resolve both payees, to the same payee. This is the "did the same payment get captured twice?" check
 * the Review focus queue surfaces. Pure + unit-tested.
 *
 * Returns the nearest twin (preferring the earliest = the likely original the copy duplicates), or null.
 * The reconciler already merges a11y↔SMS pairs by RRN inside its match window; this is the human-glance
 * backstop for the cases it left as two rows.
 */
object DuplicateDetection {
    /** ±10 minutes — matches the "same amount within ±10 min" rule in the redesign brief. */
    const val DEFAULT_WINDOW_MS = 10 * 60_000L

    fun twin(
        target: TransactionEntity,
        all: List<TransactionEntity>,
        windowMs: Long = DEFAULT_WINDOW_MS,
    ): TransactionEntity? {
        if (target.status == TxnStatus.DISCARDED) return null
        val targetKey = payeeKey(target)
        return all.asSequence()
            .filter { it.id != target.id }
            .filter { it.status != TxnStatus.DISCARDED }
            .filter { it.amountPaise == target.amountPaise && it.direction == target.direction }
            .filter { abs(it.timestampEvent - target.timestampEvent) <= windowMs }
            // Guard against coincidental same-amount payments to *different* counterparties in the window:
            // require a payee match only when BOTH rows resolve a key (else amount+time+direction stands).
            .filter { cand ->
                val candKey = payeeKey(cand)
                targetKey == null || candKey == null || candKey == targetKey
            }
            .minByOrNull { it.timestampEvent }
    }

    /** Lenient identity for the dup guard: VPA localpart, else the lowercased payee name.
     *  Also the payee half of [com.goushik.upiwallet.util.Backup]'s restore content-dedupe. */
    internal fun payeeKey(t: TransactionEntity): String? =
        t.payeeVpa?.substringBefore('@')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: t.payeeName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
