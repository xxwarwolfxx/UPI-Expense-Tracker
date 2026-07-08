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
}
