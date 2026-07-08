package com.goushik.upiwallet.domain.categorize

import android.util.Log
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.Payee

/**
 * Categorize every still-uncategorized row (`category IS NULL`, not DISCARDED). Idempotent and
 * manual-safe: [TransactionRepository.setCategory] is guarded by `category IS NULL`, so a re-run never
 * touches a row a previous pass (or a manual fix) already set. Runs on app start (backfills rows captured
 * before the categorizer existed — every seeded/historical row is Uncategorized today) and after each
 * capture, always off the main thread via the caller's coroutine scope.
 *
 * The label↔enum bridge is centralized here: learned rules are read via [Category.fromLabel] and results
 * are persisted with [Category.label] — never `.name`.
 */
object Categorization {

    suspend fun run(repo: TransactionRepository) {
        val rows = repo.uncategorizedTransactions()
        if (rows.isNotEmpty()) {
            val profile = repo.profile()
            val ownVpas = profile?.ownVpaSet() ?: emptySet()
            val ownNames = profile?.ownNameSet() ?: emptySet()
            val learned: Map<String, Category> = repo.merchantRules()
                .mapNotNull { rule -> Category.fromLabel(rule.category)?.let { rule.matchKey to it } }
                .toMap()

            var n = 0
            for (txn in rows) {
                val res = Categorizer.categorize(txn, ownVpas, ownNames, learned)
                n += repo.setCategory(txn.id, res.category.label, res.confidence, res.source, res.needsReview)
            }
            Log.d(TAG, "categorized $n/${rows.size} rows (${learned.size} learned rules)")
        }

        // Un-flag person (P2P) rows captured before the categorizer stopped auto-flagging them — a payment to
        // an individual is uncategorised but not "unsure". Idempotent: once cleared they're no longer needsReview,
        // so subsequent sweeps match fewer rows (and going forward the categorizer never sets the flag).
        val flaggedPersons = repo.transactions().filter {
            it.needsReview && it.status != TxnStatus.DISCARDED && Payee.isPerson(it.payeeName, it.payeeVpa)
        }.map { it.id }
        if (flaggedPersons.isNotEmpty()) {
            repo.clearNeedsReview(flaggedPersons)
            Log.d(TAG, "unflagged ${flaggedPersons.size} person (P2P) rows from review")
        }
    }

    private const val TAG = "UpiWallet"
}
