package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity

/**
 * Small pure display formatters for a transaction, shared so a payment reads the same wherever it
 * appears (detail screen + Review). View-layer only — never used in money math or matching.
 */

/** Compact signed rupees (no paise) for rows/cards: "−₹1,499" out · "+₹500" in · "₹500" self-transfer. */
fun signedRupees(amountPaise: Long, direction: Direction, isSelfTransfer: Boolean): String {
    val rupees = Money.formatParts(amountPaise).first
    return when {
        isSelfTransfer -> rupees
        direction == Direction.CREDIT -> "+$rupees"
        else -> "−$rupees"
    }
}

/** The funding account, e.g. "HDFC ••1234" — or "—" when nothing was captured. */
fun accountLabel(txn: TransactionEntity): String = when {
    txn.bankLabel != null && txn.payerAccountLast4 != null -> "${txn.bankLabel} ••${txn.payerAccountLast4}"
    txn.bankLabel != null -> txn.bankLabel
    txn.payerAccountLast4 != null -> "••${txn.payerAccountLast4}"
    else -> "—"
}

/** Human "money in / out / transfer to self". */
fun typeLabel(txn: TransactionEntity, isSelf: Boolean): String = when {
    isSelf -> "Transfer to self"
    txn.direction == Direction.CREDIT -> "Money in"
    else -> "Money out"
}

/**
 * The UPI apps whose payment screens we read, by package id — the same four the capture parsers are
 * registered for (parse/ConfirmSheetRegistry; a test keeps the two in step). Kept here rather than read
 * from PackageManager so the name survives the app being uninstalled later.
 */
internal val UPI_APP_NAMES = mapOf(
    "com.google.android.apps.nbu.paisa.user" to "Google Pay",
    "com.phonepe.app" to "PhonePe",
    "net.one97.paytm" to "Paytm",
    "com.dreamplug.androidapp" to "CRED",
)

/** Neutral name when we don't know which app a screen came from — never guess "Google Pay". */
const val UNKNOWN_UPI_APP = "Payment app"

/** "Google Pay" / "PhonePe" / "Paytm" / "CRED" for a captured screen's package, else [UNKNOWN_UPI_APP]. */
fun upiAppName(pkg: String?): String = UPI_APP_NAMES[pkg] ?: UNKNOWN_UPI_APP

/**
 * Where the row came from, in words ("PhonePe + bank SMS"). [appPkg] is the package of the row's screen
 * capture, when there is one — take it ONLY from an a11y raw capture: SMS raws keep the SENDER in the
 * same column.
 */
fun sourceLabel(source: String, appPkg: String? = null): String = when (source) {
    Source.A11Y_SMS -> "${upiAppName(appPkg)} + bank SMS"
    Source.SMS -> "Bank SMS"
    Source.A11Y -> upiAppName(appPkg)
    Source.MANUAL -> "Added manually"
    else -> source
}

// ── Your UPI IDs (Settings → You, onboarding's identity step) ──

/**
 * Adds what's typed in the UPI-ID box to [ids] — trimmed, and skipped when blank or already there
 * (case-insensitive, the way self-transfer matching compares them). The Add button uses it, and so do
 * Save / Continue, so an ID typed but never "Add"-ed is kept instead of silently dropped.
 */
fun withPendingUpiId(ids: List<String>, typed: String): List<String> {
    val v = typed.trim()
    if (v.isEmpty() || ids.any { it.equals(v, ignoreCase = true) }) return ids
    return ids + v
}

/**
 * The IDs themselves for a one-line summary, so a wrong ID is visible at a glance: "a@x · b@y", or
 * "a@x · b@y · +1 more" past [max]. An ID longer than [maxChars] is cut with an ellipsis. Null when there
 * are none (the caller says so in its own words).
 */
fun upiIdsSummary(ids: List<String>, max: Int = 2, maxChars: Int = 28): String? {
    val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return null
    val shown = clean.take(max).map { if (it.length > maxChars) it.take(maxChars - 1) + "…" else it }
    val more = clean.size - shown.size
    return (shown + listOfNotNull(if (more > 0) "+$more more" else null)).joinToString(" · ")
}
