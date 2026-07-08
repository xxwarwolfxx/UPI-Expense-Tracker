package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Every raw payload (a11y text dump, SMS body, notif extras) kept verbatim for replay / reparse. */
@Entity(tableName = "raw_events", indices = [Index(value = ["txnId"])])
data class RawEventEntity(
    @PrimaryKey val id: String,
    val txnId: String? = null,             // null until reconciled to a canonical row
    val source: String,
    val packageName: String? = null,
    val eventType: String? = null,
    val payload: String,
    val capturedAt: Long,
)
