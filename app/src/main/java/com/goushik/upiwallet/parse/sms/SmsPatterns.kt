package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction

/**
 * Shared bank-SMS rules, measured against the owner's own bank inbox (13,158 HDFC/SBI messages,
 * 2018-2026; replayed by SmsCorpusTest, which pins the result per template family).
 *
 * The 12-digit RRN anchor is deliberate — a 6+ match could grab account fragments and poison the
 * UNIQUE(rrn,direction) key.
 *
 * Word edges are spelled as ASCII look-arounds rather than `\b`: on the phone `java.util.regex` is ICU,
 * whose `\b` treats accented letters as word characters, while the JVM that runs the tests does not. SBI
 * once sent "debited@SBI" with the `@` mangled to "à"; explicit edges make both read it the same way.
 */
object SmsPatterns {
    /** "Rs.1,234.50", "Rs 9", "INR 500" — the prefixed amount most templates carry. */
    val AMOUNT = Regex("(?i)(?:Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

    /**
     * SBI's current UPI alert has NO currency prefix — "A/C X1234 debited by 250.0 on date 12Sep26 trf to
     * <payee> Refno <rrn>" — so the verb anchors the number. Checked first; templates that say
     * "debited by Rs.250" read the same number either way.
     */
    private val AMOUNT_AFTER_VERB = Regex(
        "(?i)(?<![A-Za-z])(?:debited|credited)\\s+(?:by|for|with)\\s+(?:(?:Rs\\.?|INR)\\s?)?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )

    /**
     * Money-out VERBS only. The bare noun "debit" is gone: it matched "Debit Card" in offers, declines and
     * collect requests ("To authorize debit…"), and an AutoPay "debit of Rs.X is scheduled" notice.
     * The phrase entries each keep a real debit-card template that only ever matched through that noun:
     *  - "has a debit by transfer of Rs X" (SBI core banking),
     *  - "transaction number N for Rs.X by SBI Debit Card …" (SBI card spends),
     *  - "Thank you for using your SBI Debit Card … for a purchase worth Rs X" (older SBI card spends),
     *  - "Payment of Rs X for <service> e-mandate … has been processed successfully on your SBI Debit card",
     *  - "AutoPay (E-mandate) Successful! For <service> Current Txn Amt: Rs.X … HDFC Bank Debit Card".
     */
    private val DEBIT_VERB = words(
        "debited", "sent", "spent", "paid", "withdrawn",
        "has\\s+a\\s+debit\\s+by",
        "transaction\\s+number\\s+\\S+\\s+for",
        "for\\s+a\\s+purchase\\s+worth",
        "processed\\s+successfully\\s+on\\s+your\\s+\\S+\\s+debit\\s+card",
        "current\\s+txn\\s+amt",
    )

    /**
     * Money-in VERBS only. The bare noun "credit" is gone: it matched a payee called "<Lender> Credit"
     * (a loan EMI was booked as income), and "Credit Card" in every card alert and offer.
     * "has (a) credit by/for" keeps SBI core banking's "Your A/C has a credit by Transfer of Rs X".
     */
    private val CREDIT_VERB = words(
        "credited", "deposited", "received",
        "has\\s+(?:a\\s+)?credit\\s+(?:by|for)",
    )

    /**
     * Not a transaction: a promise, a reminder, a bill, a mandate registration, an OTP, a collect request,
     * a failure, or an offer. These carry an amount and often a verb ("will be debited", "ignore if paid",
     * "received today for processing"), so they must be refused before direction is read — an AutoPay
     * "is scheduled on" notice was booked as a confirmed spend a day before any money moved.
     * The OTP forms are specific on purpose: a real card spend says "…without PIN/OTP".
     * The offer forms come from an SBI account header's reward campaign ("Earn 5X points on min Rs 500
     * spent on Debit Card"): no alert earns points or names a minimum, and "spent" made it a debit.
     */
    private val NOT_A_TRANSACTION = words(
        "is\\s+scheduled", "scheduled\\s+on",
        "will\\s+be\\s+(?:auto-)?(?:debited|deducted|processed|charged)",
        "is\\s+due", "due\\s+on", "due\\s+by", "pre-debit", "(?:amt|amount)\\s+due", "payable\\s+by",
        "is\\s+overdue", "ignore\\s+if\\s+(?:already\\s+)?paid", "has\\s+requested",
        "mandate\\s+registration", "received\\s+today\\s+for\\s+processing",
        "OTP\\s+(?:is|for|to)", "is\\s+(?:your\\s+)?OTP", "one[- ]time\\s+password",
        "declined", "failed", "could\\s+not", "unsuccessful",
        "earn\\s+\\S+\\s+points", "min(?:imum)?\\.?\\s+(?:Rs|INR)",
    )

    /**
     * The payee is named by the bank verbatim, and names say anything ("<Lender> India Credit", "PAID
     * PARKING"). The "To <payee>" segment is removed before direction is read: HDFC puts it on its own
     * line (never the first one — so a one-line text that happens to start with "To" is not wiped out);
     * single-line templates end it at "On <date>" / "Ref"/"Refno <rrn>".
     */
    private val PAYEE_LINE = Regex("(?i)(?<=\\n)[ \\t]*To(?![A-Za-z])[^\\n]*")
    private val PAYEE_INLINE = Regex("(?i)(?<![A-Za-z])to\\s+[^\\n]*?(?=\\s+(?:On|Ref|Refno)(?![A-Za-z]))")

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
    /** "To <Name> On …" (HDFC), "transfer to <NAME> Ref No" / "trf to <Name> Refno" (SBI). */
    val PAYEE_TO = Regex("(?i)\\bTo\\s+([A-Z][A-Za-z .]{1,40}?)(?:\\s+On\\b|\\s+Ref(?:no)?\\b|[.\\n]|$)")

    private val UPI_WORD = words("UPI")

    /** Standing-instruction debits: no payment screen is ever shown for these, by design. */
    private val MANDATE = words("mandate", "e-mandate", "auto\\s*-?pay", "UMRN", "NACH")

    /** True for reminders, bills, OTPs, collect requests and failures — anything that moved no money. */
    fun isNotATransaction(body: String): Boolean = NOT_A_TRANSACTION.containsMatchIn(body)

    /**
     * A UPI payment the owner would have made on a payment screen: it names a VPA or says UPI, and it is
     * not a mandate/AutoPay execution (those debit silently, with no screen to capture). Used to decide
     * whether an SMS-only debit means "capture is on but missed this one".
     */
    fun isScreenPaidUpi(body: String, payeeVpa: String?): Boolean =
        (payeeVpa != null || UPI_WORD.containsMatchIn(body)) && !MANDATE.containsMatchIn(body)

    /**
     * Direction from the verb, with the payee segment ignored. When both kinds of verb appear the EARLIEST
     * wins: "is debited for Rs.X … and a/c XXXX credited" is a debit.
     */
    fun direction(body: String): Direction? {
        val cleaned = PAYEE_INLINE.replace(PAYEE_LINE.replace(body, " "), " ")
        val debit = DEBIT_VERB.find(cleaned)?.range?.first
        val credit = CREDIT_VERB.find(cleaned)?.range?.first
        return when {
            debit != null && credit != null -> if (debit < credit) Direction.DEBIT else Direction.CREDIT
            debit != null -> Direction.DEBIT
            credit != null -> Direction.CREDIT
            else -> null
        }
    }

    /** The transaction amount as the rupee string ("1,234.50"), verb-anchored first, then Rs/INR. */
    fun amount(body: String): String? =
        (AMOUNT_AFTER_VERB.find(body) ?: AMOUNT.find(body))?.groupValues?.get(1)

    fun rrn(body: String): String? =
        RRN_CTX.find(body)?.groupValues?.get(1) ?: RRN_BARE.find(body)?.groupValues?.get(1)

    /** Alternatives joined with ASCII word edges (see the object KDoc), case-insensitive. */
    private fun words(vararg alternatives: String) =
        Regex("(?i)(?<![A-Za-z])(?:${alternatives.joinToString("|")})(?![A-Za-z])")
}
