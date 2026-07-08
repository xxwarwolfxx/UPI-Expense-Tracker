package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    /** Reactive — the Budgets screen renders live progress as payments land. */
    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    /** One-shot for the write-path alert check. */
    @Query("SELECT * FROM budgets")
    suspend fun all(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE period = :period")
    suspend fun delete(period: String)

    /** Record that we've nudged [threshold] for the window starting [periodStart] — the dedup marker. */
    @Query("UPDATE budgets SET lastAlertedThreshold = :threshold, lastAlertedPeriodStart = :periodStart WHERE period = :period")
    suspend fun markAlerted(period: String, threshold: Int, periodStart: Long)
}
