package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RawEventDao {
    @Insert
    suspend fun insert(e: RawEventEntity)

    @Query("UPDATE raw_events SET txnId = :txnId WHERE id = :id")
    suspend fun attach(id: String, txnId: String)

    @Query("SELECT * FROM raw_events WHERE txnId = :txnId ORDER BY capturedAt ASC")
    suspend fun forTxn(txnId: String): List<RawEventEntity>
}
