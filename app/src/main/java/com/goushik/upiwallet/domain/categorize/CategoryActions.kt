package com.goushik.upiwallet.domain.categorize

import com.goushik.upiwallet.data.MerchantRuleEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository

/**
 * The "fix the category" dual-write, lifted out of the detail screen so the Review focus queue reuses it
 * verbatim: set the manual category (clears `needsReview`) AND teach a learned rule keyed by the SAME
 * [Categorizer.matchKey] the pipeline looks up, so the next payment to this payee auto-files itself.
 */
object CategoryActions {
    suspend fun setManual(repo: TransactionRepository, txn: TransactionEntity, label: String) {
        repo.setCategoryManual(txn.id, label)
        Categorizer.matchKey(txn)?.let { key ->
            repo.upsertMerchantRule(MerchantRuleEntity(key, label, "user", System.currentTimeMillis()))
        }
    }
}
