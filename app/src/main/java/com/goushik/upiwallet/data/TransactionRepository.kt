package com.goushik.upiwallet.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The on-device source of truth. Exposes persistence primitives + a race-safe transactional entry
 * point; the Reconciler (async dedup) and BalanceCalculator build on top of these.
 */
class TransactionRepository(val db: AppDatabase) {
    private val txnDao = db.transactionDao()
    private val rawDao = db.rawEventDao()
    private val anchorDao = db.balanceAnchorDao()
    private val profileDao = db.userProfileDao()
    private val merchantRuleDao = db.merchantRuleDao()
    private val budgetDao = db.budgetDao()

    fun observeTransactions(): Flow<List<TransactionEntity>> = txnDao.observeAll()
    /** One-shot read for non-reactive callers (the home-screen widget). */
    suspend fun transactions(): List<TransactionEntity> = txnDao.allOnce()
    fun observeReviewTransactions(): Flow<List<TransactionEntity>> = txnDao.observeNeedsReview()
    fun observeAnchors(): Flow<List<BalanceAnchorEntity>> = anchorDao.observeAll()
    fun observeProfile(): Flow<UserProfileEntity?> = profileDao.observe()

    suspend fun anchors(): List<BalanceAnchorEntity> = anchorDao.all()
    suspend fun anchorCount(): Int = anchorDao.count()
    suspend fun upsertAnchor(a: BalanceAnchorEntity) = anchorDao.upsert(a)
    suspend fun clearAnchors() = anchorDao.clear()

    suspend fun profile(): UserProfileEntity? = profileDao.get()
    suspend fun upsertProfile(p: UserProfileEntity) = profileDao.upsert(p)

    // ── Categorization (Phase 3) ──
    suspend fun uncategorizedTransactions(): List<TransactionEntity> = txnDao.uncategorized()
    suspend fun setCategory(id: String, category: String, conf: Float, source: String, needsReview: Boolean): Int =
        txnDao.setCategoryIfUncategorized(id, category, conf, source, needsReview)
    suspend fun setCategoryManual(id: String, category: String): Int = txnDao.setCategoryManual(id, category)
    suspend fun clearNeedsReview(ids: List<String>): Int = txnDao.clearNeedsReview(ids)
    /** Mark a row DISCARDED (Review "Remove this copy"): drops it from the queue + all totals; raw kept. */
    suspend fun discard(id: String): Int = txnDao.discard(id)
    suspend fun merchantRules(): List<MerchantRuleEntity> = merchantRuleDao.all()
    suspend fun upsertMerchantRule(rule: MerchantRuleEntity) = merchantRuleDao.upsert(rule)

    // ── Budgets (Phase 2) ──
    fun observeBudgets(): Flow<List<BudgetEntity>> = budgetDao.observeAll()
    suspend fun budgets(): List<BudgetEntity> = budgetDao.all()
    suspend fun upsertBudget(b: BudgetEntity) = budgetDao.upsert(b)
    suspend fun deleteBudget(period: String) = budgetDao.delete(period)
    suspend fun markBudgetAlerted(period: String, threshold: Int, periodStart: Long) =
        budgetDao.markAlerted(period, threshold, periodStart)

    suspend fun insertRaw(e: RawEventEntity) = rawDao.insert(e)
    suspend fun insertTransaction(t: TransactionEntity): Long = txnDao.insert(t)
    suspend fun transactionById(id: String) = txnDao.byId(id)
    fun observeTransactionById(id: String): Flow<TransactionEntity?> = txnDao.observeById(id)
    suspend fun rawEventsFor(txnId: String): List<RawEventEntity> = rawDao.forTxn(txnId)

    /** Run find→claim→merge atomically (single SQLite writer serializes concurrent reconcilers). */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction(block)
}
