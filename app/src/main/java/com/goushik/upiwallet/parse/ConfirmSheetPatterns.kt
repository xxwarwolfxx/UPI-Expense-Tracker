package com.goushik.upiwallet.parse

/**
 * Shared text anchors for the a11y confirm-sheet parsers (Phase 4). A plain constant holder — NOT a base
 * parser: each app keeps its own [ConfirmSheetParser.qualify]/[ConfirmSheetParser.parse] plus its own
 * Pay-token / payee / account-tail anchors (the apps' confirm screens differ enough that a configurable
 * base would just special-case anyway). Only what is genuinely common lives here: the payer-bank list and
 * the outcome-headline test.
 */
internal object ConfirmSheetPatterns {
    /** Payer-bank line — the major Indian banks. Case-insensitive, so it also covers PhonePe's
     *  "State Bank of India" and CRED's "State Bank Of India". A LABEL source, not a gate: Google Pay
     *  used to require a match here and so recorded nothing for any bank missing from this list. */
    val BANK = Regex(
        "(?i)\\b(HDFC|State Bank of India|SBI|ICICI|Axis|Kotak|Punjab National|PNB|" +
            "Bank of Baroda|Canara|Yes Bank|IDFC|IndusInd|Union Bank)\\b",
    )

    // A result headline is a node of its own, so it is a whole line of the flattened text. Whole-line on
    // purpose: a promo such as "Payment failed? Get an instant refund" or a payee called "Success Traders"
    // must not make a live pay sheet look finished. "Paid to <name>" is Paytm's success line, so that one
    // only has to START the line.
    private const val WS = "[ \\t\\u00A0\\u202F]"
    private val OUTCOME_HEADLINE = Regex(
        "(?im)^$WS*(?:" +
            "((?:payment|transaction)$WS+(?:successful|success|failed|declined|unsuccessful|cancell?ed)" +
            "|payment could not be completed|sent successfully)$WS*[!.]?$WS*$" +
            "|(paid to)$WS+\\S" +
            ")",
    )

    /**
     * The outcome headline on [text] ("payment successful", "payment failed", "paid to", ...), or null.
     *
     * A screen that REPORTS how a payment ended is not asking for one, even when the sheet it was drawn
     * over still sits in the accessibility tree: PhonePe paints "Payment Successful" on top of its pay
     * sheet, and the flattened text still carries that sheet's "Pay ₹X". Without this test such a screen
     * qualified as a brand-new payment — two stored captures in the owner's ledger are exactly that. Every
     * parser rejects on it, and the capture service resolves the open episode from it instead.
     *
     * Corpus-checked: of the ~600 stored screens carrying a "Pay ₹" button across all four apps, only those
     * two PhonePe overlays carry any headline.
     */
    fun outcomeHeadline(text: String): String? =
        OUTCOME_HEADLINE.find(text)?.let { (it.groups[1] ?: it.groups[2])?.value?.lowercase() }
}
