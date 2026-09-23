package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus

/**
 * Spots a payment screen the owner most likely backed out of: the row was booked the moment the pay screen
 * appeared (so a mid-PIN process kill can't lose a real payment), but nothing ever proved it went through,
 * and a fresh payment screen in the SAME app opened seconds later — the usual shape of "typed ₹510, saw the
 * typo, backed out, paid ₹210".
 *
 * The owner's decision (2026-09-23): such a row still COUNTS — the detector never removes anything. It only
 * raises "Did this go through?" in Review, where Keep leaves it counted and Remove soft-deletes it
 * (reversible). Pure + unit-tested; measured on the owner's ledger before shipping.
 *
 * A row is flagged when ALL of:
 *  - it came from a payment screen alone ([Source.A11Y]) and carries no bank reference — any RRN is the
 *    bank's proof, so a bank-confirmed row can never be flagged;
 *  - its status is PENDING or UNCONFIRMED (a success screen would have made it CONFIRMED);
 *  - we know which app it came from, and another screen capture from that same app, not removed, started
 *    within [WINDOW_MS] after it (strictly later — a tie proves nothing);
 *  - it is at least [SETTLE_MS] old, so the bank SMS has had its chance to merge first (asking about a
 *    payment the bank confirms a minute later is just noise);
 *  - the owner hasn't already answered Keep for it ([kept]).
 *
 * Rows with no known app (captures made before the app name was stored) are left alone: guessing the app
 * from time alone would pair unrelated payments.
 */
object BackedOutDetection {
    /** How soon after the first screen the second must start. ~1 minute is how long a correction takes. */
    const val WINDOW_MS = 60_000L

    /** Past the reconciler's SMS match window (90 s + 10 min margin) with room to spare. */
    const val SETTLE_MS = 15 * 60_000L

    /** A flagged [row], the [next] capture that followed it, and the app both came from. */
    data class Flag(val row: TransactionEntity, val next: TransactionEntity, val appPkg: String) {
        val gapMs: Long get() = next.timestampEvent - row.timestampEvent
    }

    /**
     * @param appOf which UPI app each screen-captured row came from (txn id → package), from its raw capture.
     * @return the flags, newest first.
     */
    fun flag(
        all: List<TransactionEntity>,
        appOf: Map<String, String>,
        now: Long,
        kept: Set<String> = emptySet(),
        windowMs: Long = WINDOW_MS,
    ): List<Flag> {
        // Every live screen capture with a known app, oldest first — the pool a follower is drawn from.
        val captures = all
            .filter { it.status != TxnStatus.DISCARDED && isScreenCapture(it) && appOf[it.id] != null }
            .sortedBy { it.timestampEvent }

        val flags = mutableListOf<Flag>()
        captures.forEachIndexed { i, row ->
            if (!isCandidate(row, now) || row.id in kept) return@forEachIndexed
            val app = appOf.getValue(row.id)
            var j = i + 1
            while (j < captures.size) {
                val next = captures[j]
                val gap = next.timestampEvent - row.timestampEvent
                if (gap > windowMs) break
                if (gap > 0 && appOf[next.id] == app) {
                    flags += Flag(row, next, app)
                    break
                }
                j++
            }
        }
        return flags.sortedByDescending { it.row.timestampEvent }
    }

    private fun isCandidate(t: TransactionEntity, now: Long): Boolean =
        t.source == Source.A11Y &&
            t.rrn == null &&
            (t.status == TxnStatus.PENDING || t.status == TxnStatus.UNCONFIRMED) &&
            now - t.timestampEvent >= SETTLE_MS

    /** A row that began as a payment screen (whether or not the bank's SMS later merged into it). */
    private fun isScreenCapture(t: TransactionEntity): Boolean =
        t.source == Source.A11Y || t.source == Source.A11Y_SMS
}
