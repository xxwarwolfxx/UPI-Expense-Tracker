package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction

/**
 * Shared bank-SMS regexes. Derived from the Phase-0.5 captured samples (HDFC/SBI credit) plus the
 * standard debit templates. The 12-digit RRN anchor is deliberate — a 6+ match could grab account
 * fragments and poison the UNIQUE(rrn,direction) key.
 *
 * UNVALIDATED: Phase 0.5 only ever captured *credit* SMS. The debit templates here are assumed and must
 * be hardened against a real captured >Rs.100 debit SMS (the verification pre-step) before being trusted.
 */
object SmsPatterns {
    val AMOUNT = Regex("(?i)(?:Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val DEBIT_KW = Regex("(?i)\\b(debited|sent|spent|paid|withdrawn|debit)\\b")
    private val CREDIT_KW = Regex("(?i)\\b(credited|deposited|received|credit)\\b")

    // Context-anchored first, then a lone 12-digit token (masked accounts are 3–4 digits, never 12).
    private val RRN_CTX = Regex(
        "(?i)(?:upi(?:\\s*ref(?:erence)?\\s*(?:no|id)?\\.?)?|ref(?:erence)?\\s*(?:no|id)?\\.?|rrn)[:.\\s]*([0-9]{12})\\b"
    )
    private val RRN_BARE = Regex("\\b([0-9]{12})\\b")

    val VPA = Regex("([a-zA-Z0-9._-]+@[a-zA-Z]{2,})")
    val LAST4 = Regex("(?i)A/?[Cc]\\.?\\s*[X*x]*([0-9]{3,4})")
    val AVL_BAL = Regex(
        "(?i)(?:Avl\\s*bal|Avbl\\s*Bal|Available\\s*Bal(?:ance)?)[:.\\s]*(?:Rs\\.?|INR)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )
    val PAYEE_TO = Regex("(?i)\\bTo\\s+([A-Z][A-Za-z .]{1,40}?)(?:\\s+On\\b|\\s+Ref\\b|[.\\n]|$)")

    fun direction(body: String): Direction? = when {
        CREDIT_KW.containsMatchIn(body) -> Direction.CREDIT
        DEBIT_KW.containsMatchIn(body) -> Direction.DEBIT
        else -> null
    }

    fun rrn(body: String): String? =
        RRN_CTX.find(body)?.groupValues?.get(1) ?: RRN_BARE.find(body)?.groupValues?.get(1)
}
