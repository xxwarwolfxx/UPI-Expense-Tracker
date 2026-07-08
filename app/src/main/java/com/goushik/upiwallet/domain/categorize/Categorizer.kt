package com.goushik.upiwallet.domain.categorize

import com.goushik.upiwallet.data.Direction

/** Spend buckets. `label` is the canonical display string; persisted as a String on the row. */
enum class Category(val label: String) {
    GROCERIES("Groceries"), FOOD("Food"), TRANSPORT("Transport"),
    BILLS("Bills & Utilities"), RENT("Rent"), ENTERTAINMENT("Entertainment"),
    SHOPPING("Shopping"), HEALTH("Health"), TRAVEL("Travel"),
    SUBSCRIPTIONS("Subscriptions"), SELF_TRANSFER("Transfer-to-self"),
    OTHER("Other");
    companion object { fun fromLabel(l: String?): Category? = entries.firstOrNull { it.label == l } }
}

/** Outcome of one categorize() run. `source` is the rule that won; `confidence` drives needsReview. */
data class CategoryResult(val category: Category, val confidence: Float, val source: String, val needsReview: Boolean)

/**
 * Ordered, first-match-wins categorizer (mirrors ParserRegistry's pipeline style). Pure function — no DB,
 * no IO, no Android beyond the data types. Self-transfer detection is reused verbatim from
 * BalanceCalculator so categorization stays identical to the balance-netting definition.
 */
object Categorizer {

    fun categorize(
        txn: com.goushik.upiwallet.data.TransactionEntity,
        ownVpas: Set<String>, ownNames: Set<String>,
        learned: Map<String, Category>,   // key (vpa-localpart, else lowercased payeeName) -> Category
    ): CategoryResult {
        // 1. self-transfer — same definition as the wallet's balance netting; never a spend.
        if (com.goushik.upiwallet.domain.BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames)) {
            return result(Category.SELF_TRANSFER, 1.0f, "self")
        }

        // 2. credits are income, not spends — don't merchant-match them.
        if (txn.direction == Direction.CREDIT) {
            return result(Category.OTHER, 0.5f, "income")
        }

        // 3. learned memory — a past manual fix on this counterparty wins over the dictionary.
        val key = matchKey(txn)
        if (key != null) learned[key]?.let { return result(it, 0.95f, "memory") }

        // 4. exact full-VPA match.
        txn.payeeVpa?.let { vpa ->
            MerchantDictionary.byExactVpa(vpa)?.let { return result(it, 0.9f, "vpa") }
        }

        // 5. token/substring on the VPA localpart (specific-before-generic, per the dictionary).
        txn.payeeVpa?.let { vpa ->
            MerchantDictionary.byVpaToken(MerchantDictionary.localPart(vpa))?.let { return result(it, 0.8f, "vpa-token") }
        }

        // 6. token match on the display name.
        txn.payeeName?.let { name ->
            MerchantDictionary.byCounterpartyName(name)?.let { return result(it, 0.7f, "counterparty") }
        }

        // 7. known payment-aggregator handle but the underlying merchant is opaque — flag for review.
        txn.payeeVpa?.let { vpa ->
            if (MerchantDictionary.isAggregatorVpa(vpa)) return result(Category.OTHER, 0.3f, "aggregator-unknown")
        }

        // 8. nothing matched. A payment to an individual (P2P) is uncategorised but NOT "unsure" — keep it
        //    OUT of Review (only genuinely-suspect rows belong there). An opaque business stays flagged.
        if (com.goushik.upiwallet.domain.Payee.isPerson(txn.payeeName, txn.payeeVpa)) {
            return result(Category.OTHER, 0.6f, "person")
        }
        return result(Category.OTHER, 0.2f, "fallback")
    }

    /**
     * The learned-map key for a row: VPA localpart, else the lowercased payee name. The fix-category write
     * path MUST derive its MerchantRule.matchKey via THIS function — otherwise the stored key won't equal
     * the row's key and learned rules silently never match (step 3).
     */
    fun matchKey(txn: com.goushik.upiwallet.data.TransactionEntity): String? =
        txn.payeeVpa?.let { MerchantDictionary.localPart(it) } ?: txn.payeeName?.lowercase()?.trim()

    /** needsReview iff confidence < 0.5f OR an opaque aggregator (so only steps 7 & 8 flag). */
    private fun needsReview(confidence: Float, source: String): Boolean =
        confidence < 0.5f || source == "aggregator-unknown"

    private fun result(category: Category, confidence: Float, source: String): CategoryResult =
        CategoryResult(category, confidence, source, needsReview(confidence, source))
}
