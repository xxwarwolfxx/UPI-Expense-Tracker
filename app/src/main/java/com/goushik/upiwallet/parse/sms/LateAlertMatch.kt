package com.goushik.upiwallet.parse.sms

/**
 * A bank alert the BANK sent late — not one the network held (that is [SmsTiming]'s send-time window). Its
 * network stamp is on time, so the Reconciler's usual search covers only the few minutes before it, and a
 * pay screen captured an hour earlier is missed: the payment is booked twice, once by the screen and once
 * by the text. In the owner's ledger one SBI alert of 17 in the capture period came 57 min after its screen.
 *
 * Time alone cannot pair rows that far apart (paying the same amount twice in an afternoon is ordinary), so
 * this longer look-back also demands the SAME PAYEE, and the Reconciler also refuses a row captured from a
 * different bank. With both rules, a replay of the owner's texts against his ledger pairs exactly that one
 * alert and nothing else (LateAlertCorpusTest). Pure strings, so the rules are host-testable.
 */
object LateAlertMatch {
    /** How long before the bank sent its alert the pay screen may sit and still be the same payment. */
    const val LOOKBACK_MS = 3 * 60 * 60 * 1000L

    /** Below this a name prefix says nothing ("M", "Ram" would match half the contacts in a phone). */
    private const val MIN_PREFIX = 4

    /** Where the late pass looks: [LOOKBACK_MS] before the send time, plus the usual clock skew after it. */
    fun window(sentAt: Long): SmsTiming.Window =
        SmsTiming.Window(from = sentAt - LOOKBACK_MS, to = sentAt + SmsTiming.CLOCK_SKEW_MS, pivot = sentAt)

    /**
     * Is the payee the bank named the payee the pay screen showed? When both sides carry a UPI ID, its user
     * part decides. Otherwise the names, compared case- and punctuation-blind, must agree up to the shorter
     * one: banks cut names (HDFC at 25 characters, mid-word) and apps shorten them, so one is a prefix of the
     * other. A side with neither an ID nor a name is never a match — unknown is not the same.
     */
    fun samePayee(smsName: String?, smsVpa: String?, rowName: String?, rowVpa: String?): Boolean {
        val smsUser = vpaUser(smsVpa)
        val rowUser = vpaUser(rowVpa)
        if (smsUser != null && rowUser != null) return smsUser == rowUser
        val a = normalName(smsName) ?: return false
        val b = normalName(rowName) ?: return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= MIN_PREFIX && long.startsWith(short)
    }

    private fun vpaUser(vpa: String?): String? =
        vpa?.substringBefore('@')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    private fun normalName(name: String?): String? =
        name?.lowercase()?.replace(NOT_ALNUM, " ")?.trim()?.takeIf { it.isNotEmpty() }
}
