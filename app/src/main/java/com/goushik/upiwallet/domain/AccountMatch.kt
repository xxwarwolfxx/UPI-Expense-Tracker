package com.goushik.upiwallet.domain

/**
 * Maps a transaction's captured `bankLabel` to one of the user's set-up accounts (anchors). Needed because
 * the SAME bank shows up under different labels depending on the capture path — e.g. the SBI SMS parser
 * writes "SBI" while the GPay confirm-sheet parser captures "State Bank of India". We canonicalise both
 * sides to a short key before comparing, so per-account spend attribution doesn't silently undercount.
 */
object AccountMatch {

    /** Canonical short key for a bank label ("State Bank of India" / "SBI" → "sbi"). */
    fun normalizeBank(label: String): String {
        val l = label.lowercase()
        return when {
            "hdfc" in l -> "hdfc"
            "sbi" in l || "state bank" in l -> "sbi"
            "icici" in l -> "icici"
            "axis" in l -> "axis"
            "kotak" in l -> "kotak"
            "pnb" in l || "punjab national" in l -> "pnb"
            else -> l.trim()
        }
    }

    /** True when a transaction's captured bank label belongs to the given account (anchor) label. */
    fun matches(txnBankLabel: String?, accountLabel: String): Boolean =
        txnBankLabel != null && normalizeBank(txnBankLabel) == normalizeBank(accountLabel)
}
