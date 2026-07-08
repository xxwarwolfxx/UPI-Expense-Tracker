package com.goushik.upiwallet.data

/** Spend vs income. A confirm-sheet capture is always a DEBIT; credits arrive only via SMS. */
enum class Direction { DEBIT, CREDIT }

/**
 * Lifecycle of a captured transaction.
 * - PENDING      captured (confirm sheet), episode still open / no proof yet
 * - CONFIRMED    positive proof seen (success token) or matched by an authoritative SMS
 * - UNCONFIRMED  episode ended without proof (e.g. <=Rs.100, no SMS, unreadable success screen)
 * - DISCARDED    explicit failure / cancel observed
 */
enum class TxnStatus { PENDING, CONFIRMED, UNCONFIRMED, DISCARDED }

/** Provenance tag stored on the canonical row. */
object Source {
    const val A11Y = "a11y"
    const val SMS = "sms"
    const val A11Y_SMS = "a11y+sms"
    const val MANUAL = "manual"   // user-entered via the "+" button (no RRN; CONFIRMED on save)
}
