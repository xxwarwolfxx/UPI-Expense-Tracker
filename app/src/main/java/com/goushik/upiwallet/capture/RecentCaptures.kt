package com.goushik.upiwallet.capture

import com.goushik.upiwallet.data.TxnStatus

/**
 * A tiny time-to-live ring of the (app, amount) pairs recently turned into rows.
 *
 * It replaces two ad-hoc guards that between them left a hole: the service only remembered the ONE open
 * episode plus the ONE last-resolved amount, so a sheet re-rendering as ₹100 → ₹250 → ₹100 inside a few
 * seconds booked three rows — the ₹250 evicted the open ₹100 episode, and nothing had resolved yet, so the
 * repeat ₹100 sailed through. Remembering the last few captures stops the REPEAT ₹100 — honestly: the
 * transient ₹250 in the middle still books its own row (a different amount is indistinguishable from a
 * different payment at this layer); that row rides as PENDING and ages out unconfirmed.
 *
 * Deliberately kept in memory and deliberately short-lived. A genuine repeat payment of the same amount to
 * the same app minutes apart is real and must still be recorded — suppressing it would under-count, which
 * lies about the totals just as badly as over-counting. The longer-range duplicates (a receipt re-read
 * minutes after the payment) are stopped by the parser's action-anchor rule, not here.
 *
 * For the same reason a FAILED payment is forgotten — see [settle]: the natural next move after "Payment
 * failed" is to try again straight away, and that retry is a new payment that must get its own row.
 *
 * Not thread-safe by design: accessibility callbacks all arrive on the main thread.
 */
class RecentCaptures(
    private val ttlMs: Long,
    private val capacity: Int = 8,
    private val failureHoldMs: Long = FAILURE_HOLD_MS,
) {
    private data class Entry(val pkg: String, val amountPaise: Long, val expiresAt: Long)

    private val entries = ArrayDeque<Entry>()

    /** True when this exact (app, amount) was recorded and its entry has not expired yet. */
    fun seenRecently(pkg: String, amountPaise: Long, now: Long): Boolean {
        prune(now)
        return entries.any { it.pkg == pkg && it.amountPaise == amountPaise }
    }

    /**
     * Record a capture, held for [forMs] (the ring's TTL unless a caller needs a shorter hold). Oldest
     * entries fall off once [capacity] is reached.
     */
    fun remember(pkg: String, amountPaise: Long, now: Long, forMs: Long = ttlMs) {
        prune(now)
        entries.addLast(Entry(pkg, amountPaise, now + forMs))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Drop every entry for this (app, amount) — used when a payment FAILED, so a retry can record. */
    fun forget(pkg: String, amountPaise: Long) {
        entries.removeAll { it.pkg == pkg && it.amountPaise == amountPaise }
    }

    /**
     * The open episode for (app, amount) has just been resolved to [status]; re-arm the ring accordingly.
     *
     *  - **CONFIRMED** holds the full TTL: apps flash the confirm sheet again during their success
     *    animation, and without the hold that flash would book a second row for the payment that just
     *    finished.
     *  - **DISCARDED** (the payment failed) drops every entry for the pair — including the one written
     *    when the sheet first qualified, which alone would block a retry for the rest of its TTL — and
     *    holds it again for only [failureHoldMs], enough to absorb the failure screen re-rendering. A
     *    retry after that opens a new episode, as it must: under the old full hold, "failed → retry at
     *    +15 s" recorded nothing, and only an HDFC/SBI user's bank SMS could put that payment back.
     *  - Unless [sheetStillShowing]: when the very screen that resolved the episode still carries this
     *    amount's live `Pay ₹` sheet, the full hold stays whatever the status, so the sheet cannot reopen
     *    an episode on its next re-render (one payment, a row per re-render).
     *
     * Accepted trade: if an app re-shows the same pay sheet after a failure and the user walks away, that
     * re-shown sheet can open a second PENDING row after the short hold. No stored screen shows an app
     * doing that, while a quick retry after a failure is the ordinary thing to do.
     */
    fun settle(pkg: String, amountPaise: Long, now: Long, status: TxnStatus, sheetStillShowing: Boolean) {
        forget(pkg, amountPaise)
        val hold = if (status == TxnStatus.CONFIRMED || sheetStillShowing) ttlMs else failureHoldMs
        remember(pkg, amountPaise, now, hold)
    }

    /** Test/diagnostic view: how many live entries are held right now. */
    fun size(now: Long): Int {
        prune(now)
        return entries.size
    }

    // Entries can carry different holds, so expiry is checked on every entry, not just the oldest.
    private fun prune(now: Long) {
        entries.removeAll { now >= it.expiresAt }
    }

    companion object {
        /** How long a FAILED payment's amount stays suppressed — a failure screen's re-render, not a retry. */
        const val FAILURE_HOLD_MS = 5_000L
    }
}
