package com.goushik.upiwallet.parse

/**
 * Shared text anchors for the a11y confirm-sheet parsers (Phase 4). A plain constant holder — NOT a base
 * parser: each app keeps its own [ConfirmSheetParser.qualify]/[ConfirmSheetParser.parse] plus its own
 * Pay-token / payee / account-tail anchors (the apps' confirm screens differ enough that a configurable
 * base would just special-case anyway). Only the payer-bank list is genuinely common, so it lives here.
 */
internal object ConfirmSheetPatterns {
    /** Payer-bank line — the major Indian banks. Case-insensitive, so it also covers PhonePe's
     *  "State Bank of India" and CRED's "State Bank Of India". */
    val BANK = Regex(
        "(?i)\\b(HDFC|State Bank of India|SBI|ICICI|Axis|Kotak|Punjab National|PNB|" +
            "Bank of Baroda|Canara|Yes Bank|IDFC|IndusInd|Union Bank)\\b",
    )
}
