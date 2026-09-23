package com.goushik.upiwallet.domain.categorize

import android.util.Log
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.BalanceCalculator
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
        val profile = repo.profile()
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()

        // A "Transfer-to-self" label is written once, but whether a row IS one can change later: the name
        // rule went from substring to whole words, and the name and UPI IDs in the profile can be edited. A
        // row that now counts as spend must not keep the label, or Insights shows a "Transfer-to-self" slice
        // inside spend and the CSV contradicts itself. Clearing the category lets the pass below
        // re-categorise it at once. Idempotent: once cleared, the row no longer carries the label.
        // Skipped until there is a profile, so nothing is judged against an identity not set up yet.
        if (profile != null) {
            val stale = staleSelfTransferIds(repo.transactions(), ownVpas, ownNames)
            if (stale.isNotEmpty()) {
                val cleared = clearCategories(repo, stale)
                Log.d(TAG, "cleared a stale Transfer-to-self label on $cleared/${stale.size} rows")
            }
        }

        val rows = repo.uncategorizedTransactions()
        if (rows.isNotEmpty()) {
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

    /**
     * Live rows labelled "Transfer-to-self" that are no longer a transfer to yourself under the current rule
     * and identity. Keyed on the label whatever wrote it (the categorizer's "self", or a sample fixture's
     * "manual"): no screen lets the user pick this label by hand, so none of these is a user's own choice.
     */
    fun staleSelfTransferIds(
        rows: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
    ): List<String> = rows.filter {
        it.status != TxnStatus.DISCARDED &&
            it.category == Category.SELF_TRANSFER.label &&
            !BalanceCalculator.isSelfTransfer(it, ownVpas, ownNames)
    }.map { it.id }

    /**
     * Clear category, confidence and source on [ids], in one transaction. Each row is re-read inside it and
     * cleared only if it still carries the label, so a category set in the meantime is never undone; and the
     * whole-row write cannot land over a concurrent change (a bank SMS merging into the row, say), because
     * the transaction holds the database's write lock from its first statement until it commits.
     */
    private suspend fun clearCategories(repo: TransactionRepository, ids: List<String>): Int =
        repo.inTransaction {
            val dao = repo.db.transactionDao()
            var n = 0
            for (id in ids) {
                val row = dao.byId(id) ?: continue
                if (row.category != Category.SELF_TRANSFER.label) continue
                dao.update(row.copy(category = null, categoryConfidence = null, categorySource = null))
                n++
            }
            n
        }

    private const val TAG = "UpiWallet"
}
