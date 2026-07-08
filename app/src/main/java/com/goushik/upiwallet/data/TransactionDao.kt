package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    /** IGNORE so a redelivered SMS hitting the UNIQUE(rrn,direction) index is a no-op, not a crash. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("SELECT * FROM transactions ORDER BY timestampEvent DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    /** Observe a single row so the detail screen reflects live edits (AI sweep, reconciler merge, a
     *  category fix, a discard) without being re-opened. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    /** One-shot snapshot (DESC by time) for non-reactive readers like the home-screen widget. */
    @Query("SELECT * FROM transactions ORDER BY timestampEvent DESC")
    suspend fun allOnce(): List<TransactionEntity>

    /** Rows the categorizer hasn't touched yet. Excludes DISCARDED (failed/cancelled — never shown). */
    @Query("SELECT * FROM transactions WHERE category IS NULL AND status != 'DISCARDED'")
    suspend fun uncategorized(): List<TransactionEntity>

    /** The Review inbox: low-confidence auto-categorizations the user should confirm. A manual fix
     *  clears the flag (setCategoryManual sets needsReview = 0), so resolved rows drop out reactively. */
    @Query("SELECT * FROM transactions WHERE needsReview = 1 AND status != 'DISCARDED' ORDER BY timestampEvent DESC")
    fun observeNeedsReview(): Flow<List<TransactionEntity>>

    /** Write an auto-categorization result. The `category IS NULL` guard makes the sweep idempotent and
     *  ensures it can NEVER clobber a manual fix (manual rows have a non-null category). */
    @Query(
        "UPDATE transactions SET category = :category, categoryConfidence = :conf, categorySource = :source, " +
            "needsReview = :needsReview WHERE id = :id AND category IS NULL",
    )
    suspend fun setCategoryIfUncategorized(
        id: String, category: String, conf: Float, source: String, needsReview: Boolean,
    ): Int

    /** Manual "fix category" from the detail screen — overrides any prior value and clears the review flag. */
    @Query(
        "UPDATE transactions SET category = :category, categoryConfidence = 1.0, categorySource = 'manual', " +
            "needsReview = 0 WHERE id = :id",
    )
    suspend fun setCategoryManual(id: String, category: String): Int

    /**
     * a11y rows eligible to merge with an arriving SMS: same amount + direction, no RRN yet, not
     * DISCARDED, captured within the one-sided window [from, to] (the SMS is always *later* than the
     * a11y row). Ordered nearest-in-time to the SMS pivot so the reconciler picks the closest one.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amount AND direction = :direction
          AND rrn IS NULL AND status != 'DISCARDED'
          AND timestampEvent BETWEEN :from AND :to
        ORDER BY ABS(timestampEvent - :pivot) ASC
        """
    )
    suspend fun findPendingMatches(
        amount: Long,
        direction: Direction,
        from: Long,
        to: Long,
        pivot: Long,
    ): List<TransactionEntity>

    /**
     * Race-safe claim + merge: stamp the RRN and raise status ONLY if the row is still unclaimed
     * (rrn IS NULL, not DISCARDED). Returns rows affected (0 = lost the race / already merged), which
     * makes the in-process pass and the WorkManager sweep idempotent. Enrichment fields use
     * COALESCE (fill-if-null) so the a11y-observed payer bank is never blind-overwritten.
     */
    @Query(
        """
        UPDATE transactions
        SET rrn = :rrn,
            status = :newStatus,
            source = 'a11y+sms',
            payeeVpa = COALESCE(payeeVpa, :payeeVpa),
            payerAccountLast4 = COALESCE(payerAccountLast4, :last4),
            bankLabel = COALESCE(bankLabel, :bankLabel),
            timestampCaptured = :capturedAt
        WHERE id = :id AND rrn IS NULL AND status != 'DISCARDED'
        """
    )
    suspend fun claimAndMerge(
        id: String,
        rrn: String,
        newStatus: TxnStatus,
        payeeVpa: String?,
        last4: String?,
        bankLabel: String?,
        capturedAt: Long,
    ): Int

    /** SMS is authoritative: a matching SMS may flip a heuristically-DISCARDED row to CONFIRMED. */
    @Query("UPDATE transactions SET status = :newStatus, rrn = :rrn, source = 'a11y+sms' WHERE id = :id")
    suspend fun overrideStatus(id: String, newStatus: TxnStatus, rrn: String): Int

    /** A DISCARDED a11y row that a later SMS proves was actually successful. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status = 'DISCARDED' AND rrn IS NULL
          AND amountPaise = :amount AND direction = :direction
          AND timestampEvent BETWEEN :from AND :to
        ORDER BY ABS(timestampEvent - :pivot) ASC LIMIT 1
        """
    )
    suspend fun findDiscardedMatch(amount: Long, direction: Direction, from: Long, to: Long, pivot: Long): TransactionEntity?

    /** Per-leg idempotency lookup for a redelivered SMS. */
    @Query("SELECT * FROM transactions WHERE rrn = :rrn AND direction = :direction LIMIT 1")
    suspend fun findByRrn(rrn: String, direction: Direction): TransactionEntity?

    /** Safety-net: age PENDING rows past the match window to UNCONFIRMED (survives process death). */
    @Query("UPDATE transactions SET status = 'UNCONFIRMED' WHERE status = 'PENDING' AND timestampEvent < :cutoff")
    suspend fun ageOutPending(cutoff: Long): Int

    /** Drop the review flag on a set of rows without touching their category — used to un-flag person/P2P
     *  payments captured before the categorizer stopped auto-flagging them. */
    @Query("UPDATE transactions SET needsReview = 0 WHERE id IN (:ids) AND needsReview = 1")
    suspend fun clearNeedsReview(ids: List<String>): Int

    /** Soft-delete a row the user confirms is a duplicate / wrong (from the Review focus queue): mark it
     *  DISCARDED so it drops from the queue, every spend total, and the balance ledger (all of which already
     *  exclude DISCARDED). The raw capture is kept, so this is reversible. */
    @Query("UPDATE transactions SET status = 'DISCARDED' WHERE id = :id")
    suspend fun discard(id: String): Int
}
