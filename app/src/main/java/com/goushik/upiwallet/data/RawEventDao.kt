package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RawEventDao {
    @Insert
    suspend fun insert(e: RawEventEntity)

    /** Restore path: IGNORE so re-importing a backup that shares ids with existing rows is a no-op,
     *  not a crash. Returns the rowid, or -1 when the row already existed (skipped). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(e: RawEventEntity): Long

    @Query("UPDATE raw_events SET txnId = :txnId WHERE id = :id")
    suspend fun attach(id: String, txnId: String)

    @Query("SELECT * FROM raw_events WHERE txnId = :txnId ORDER BY capturedAt ASC")
    suspend fun forTxn(txnId: String): List<RawEventEntity>

    /** Whole table, for a full-fidelity backup export. */
    @Query("SELECT * FROM raw_events")
    suspend fun all(): List<RawEventEntity>
}
