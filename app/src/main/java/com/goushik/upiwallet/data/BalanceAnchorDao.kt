package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceAnchorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anchor: BalanceAnchorEntity)

    @Query("SELECT * FROM balance_anchors")
    fun observeAll(): Flow<List<BalanceAnchorEntity>>

    @Query("SELECT * FROM balance_anchors")
    suspend fun all(): List<BalanceAnchorEntity>

    @Query("SELECT COUNT(*) FROM balance_anchors")
    suspend fun count(): Int

    /** Onboarding re-anchors balances from scratch — clear before inserting the entered values. */
    @Query("DELETE FROM balance_anchors")
    suspend fun clear()

    /** Remove ONE account. Callers must go through BalanceCalculator.anchorsAfterDelete first so the
     *  global balance cutoff can't move when the deleted anchor held the newest stamp. */
    @Query("DELETE FROM balance_anchors WHERE id = :id")
    suspend fun deleteById(id: String)
}
