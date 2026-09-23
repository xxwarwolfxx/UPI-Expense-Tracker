package com.goushik.upiwallet.capture

import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.parse.ScreenShape

/**
 * Decides whether a screen is proof that the OPEN episode's payment succeeded or failed.
 *
 * This replaces the old `checkTerminal`, which fired on any screen, from any app, for an episode of any
 * age, on nothing but the word "paid" or "completed". A transaction-history screen is full of those words,
 * which is how phantom rows got promoted from PENDING to CONFIRMED in the user's ledger — 8 of the 24 fake
 * rows found on 2026-08-22 were CONFIRMED, i.e. laundered by exactly this path.
 *
 * The rules are deliberately **asymmetric**, because the two mistakes cost different amounts:
 *  - **CONFIRM** is strict (same app, fresh episode, not a list screen, and the episode's own amount must be
 *    on screen). Over-confirming launders a fake into a trusted row; under-confirming is cosmetic, since a
 *    PENDING/UNCONFIRMED row still counts in every total and the bank SMS confirms it anyway.
 *  - **DISCARD** does not demand the amount. Failing to discard a genuinely failed payment leaves it
 *    counted, which inflates the totals — the very thing this whole change exists to stop. If a discard is
 *    ever wrong, the SMS path flips the row back (`Reconciler.onSms` → `findDiscardedMatch`).
 *
 * Pure and stateless so the service's episode machine finally has JVM test coverage.
 */
object TerminalDecision {

    /** Both spellings the apps use for success; one word alone never confirms — see [decide]. */
    private val SUCCESS_TOKEN =
        Regex("(?i)\\b(payment successful|paid|completed|success(?:ful)?|sent successfully)\\b")
    // "could not be completed" also contains "completed", so failure must be checked FIRST and must know
    // the uncontracted spelling — otherwise a failed payment reads as a successful one.
    private val FAIL_TOKEN =
        Regex("(?i)\\b(failed|declined|cancell?ed|unsuccessful|couldn'?t|could not)\\b")

    /**
     * @param text          the flattened screen text just read
     * @param eventPkg      the package the accessibility event came from
     * @param episodePkg    the package that opened the episode
     * @param episodeAmountPaise the amount the episode is waiting on
     * @param episodeAgeMs  how long the episode has been open
     * @param windowMs      how long a terminal screen may still resolve this episode - the caller
     *                      passes [A11yCaptureService.TERMINAL_WINDOW_MS] (3 min), NOT the 30s episode window
     * @param asksToPay     this same screen qualified as a live pay sheet (its verdict is not REJECTED)
     * @return the status to resolve the episode to, or null to leave it open
     */
    fun decide(
        text: String,
        eventPkg: String,
        episodePkg: String,
        episodeAmountPaise: Long,
        episodeAgeMs: Long,
        windowMs: Long,
        asksToPay: Boolean,
    ): TxnStatus? {
        // A screen still ASKING for money has not reported how any payment ended. Its words are the sheet's
        // own — a payee called "Success Traders", a "Payment failed? Get an instant refund" promo — and
        // reading them as an outcome let the episode's own re-rendering sheet CONFIRM a payment before the
        // PIN (so a later failure could no longer discard it) or DISCARD one still in progress. A real
        // result screen drawn over the sheet is REJECTED by its headline, so it never counts as asking.
        if (asksToPay) return null
        // A payment resolves within seconds. Anything later is a different screen session entirely, and the
        // ReconcileWorker already ages a stranded PENDING row to UNCONFIRMED.
        if (episodeAgeMs > windowMs) return null
        // Proof about GPay's payment cannot come from a different app's window.
        if (eventPkg != episodePkg) return null
        // Failure is checked BEFORE the list gate, deliberately. A real failure screen says both "failed"
        // and "will be refunded" — two outcome words — so the shape gate would swallow it, and a failed
        // payment sends no bank SMS, meaning nothing would EVER discard the row: it would count in the
        // totals forever. The trade is that a history screen showing a failed old payment can discard an
        // open episode (exactly what the old code did) — and that direction self-heals: the SMS path flips
        // a wrongly-discarded rrn-less row back to CONFIRMED (Reconciler.onSms -> findDiscardedMatch).
        if (FAIL_TOKEN.containsMatchIn(text)) return TxnStatus.DISCARDED

        // A list of past payments describes outcomes; it never reports this one.
        if (ScreenShape.describesPayments(text) != null) return null

        return when {
            SUCCESS_TOKEN.containsMatchIn(text) && mentionsAmount(text, episodeAmountPaise) ->
                TxnStatus.CONFIRMED
            else -> null
        }
    }

    /**
     * Is the episode's own amount on this screen? Matches the two ways the apps render it — "₹87" and
     * "₹87.00" — so a success screen for a *different* payment can't close this episode.
     */
    private fun mentionsAmount(text: String, amountPaise: Long): Boolean {
        val whole = amountPaise / 100
        val cents = amountPaise % 100
        val plain = if (cents == 0L) whole.toString() else "%d.%02d".format(whole, cents)
        val withDecimals = "%d.%02d".format(whole, cents)
        val grouped = groupIndian(whole)
        // The lookahead must also block ",5" and ".5" - with a bare (?![0-9]), episode ₹50 would match
        // a screen showing ₹50,000 and someone else's receipt could close this episode. The apps also
        // pad the symbol with a no-break or narrow space, which Java's \s does not cover.
        return listOf(plain, withDecimals, grouped, "$grouped.%02d".format(cents)).any { needle ->
            Regex(
                "(?i)(?:₹|Rs\\.?|INR)[\\s\\u00A0\\u202F]?" + Regex.escape(needle) +
                    "(?![0-9])(?!,[0-9])(?!\\.[0-9])",
            ).containsMatchIn(text)
        }
    }

    /** 250000 -> "2,50,000" — the Indian grouping the UPI apps print. */
    private fun groupIndian(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        val sb = StringBuilder()
        var i = head.length
        while (i > 2) {
            sb.insert(0, "," + head.substring(i - 2, i))
            i -= 2
        }
        if (i > 0) sb.insert(0, head.substring(0, i))
        return "$sb,$tail"
    }
}
