package com.goushik.upiwallet.parse.sms

/**
 * Who is allowed to put money in the ledger: the SENDER header, never the message text.
 *
 * Why: routing used to match "HDFC"/"SBI" anywhere in sender + body, so a prank text from an ordinary
 * phone ("Rs.49999 debited from HDFC Bank A/c") or a shop promo that mentions "HDFC credit cards" became
 * a confirmed spend or a fake income. Indian bank alerts arrive under registered DLT headers — a 2-letter
 * operator/circle prefix, the bank's own 6-letter header, sometimes a "-S/-T/-P" suffix (`AD-HDFCBK`,
 * `VM-SBIUPI-S`, `JDSBICRD`, even lower-case `AXhdfcbk`). Only the bank can send under its header; a
 * plain phone number can be anyone.
 *
 * The token lists come from the owner's own 13k-message bank inbox: every header that carried a real
 * account alert (debit, credit, card spend, NEFT, charges). Headers that only ever sent offers, rewards,
 * insurance or vouchers (HDFCGI, HDFCLI, SBIRWZ, SBICGV, CRED, shops…) are deliberately absent.
 *
 * Side effect worth knowing: an SBI message that quotes an `@okhdfcbank` VPA used to be claimed by the
 * HDFC parser (it ran first and matched "hdfc" in the body) and was labelled HDFC. It now routes by
 * header, so it is SBI.
 */
object BankSenders {
    const val HDFC = "HDFC"
    const val SBI = "SBI"

    private val HDFC_TOKENS = listOf("HDFCBK", "HDFCBN")

    /** SBI's account, UPI, ATM/debit-card, net-banking, YONO and NEFT headers, plus SBI Card. */
    private val SBI_TOKENS = listOf(
        "SBIUPI", "SBIINB", "ATMSBI", "SBIBNK", "CBSSBI", "SBYONO", "SBIDCM", "SBIPSG", "SBIDGT",
        "SCISMS", "SBMSMS", // older SBI UPI/debit-card headers (2018-2021)
        "SBICRD", "MYSBIC", // SBI Card
    )

    /**
     * Credit-card issuers' headers. A credit card is a loan, not the wallet: a spend on it is a debit, but
     * a "payment received … credited to your SBI Card" is the owner paying his own card bill (the bank
     * side of that payment is already a debit) and a cashback lands on the card, not in the account —
     * neither is income. So these senders may only ever produce a DEBIT.
     */
    private val CARD_TOKENS = listOf("SBICRD", "MYSBIC")

    private val PHONE_NUMBER = Regex("^\\+?[0-9][0-9 -]*$")

    /** [HDFC], [SBI] or null (unknown, blank, or a plain phone number). */
    fun bankOf(sender: String?): String? {
        val s = sender?.trim()?.uppercase() ?: return null
        if (s.isEmpty() || PHONE_NUMBER.matches(s)) return null
        return when {
            HDFC_TOKENS.any { s.contains(it) } -> HDFC
            SBI_TOKENS.any { s.contains(it) } -> SBI
            else -> null
        }
    }

    /** True for a credit-card issuer's header — see [CARD_TOKENS] for why only debits count from these. */
    fun isCardIssuer(sender: String?): Boolean {
        val s = sender?.trim()?.uppercase() ?: return false
        return bankOf(s) != null && CARD_TOKENS.any { s.contains(it) }
    }
}
