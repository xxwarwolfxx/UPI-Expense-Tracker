package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity

/**
 * Small pure display formatters for a transaction, shared so a payment reads the same wherever it
 * appears (detail screen + Review focus queue). View-layer only — never used in money math or matching.
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

/** Where the row came from, in words ("Google Pay + bank SMS"). */
fun sourceLabel(source: String): String = when (source) {
    Source.A11Y_SMS -> "Google Pay + bank SMS"
    Source.SMS -> "Bank SMS"
    Source.A11Y -> "Google Pay"
    Source.MANUAL -> "Added manually"
    else -> source
}
