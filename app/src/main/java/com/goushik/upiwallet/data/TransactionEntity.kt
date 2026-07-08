package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical transaction row.
 *
 * Dedup index: UNIQUE(rrn, direction). SQLite treats NULLs as distinct, so a11y-only / <=Rs.100 rows
 * (rrn = null) insert freely, while a real RRN enforces per-leg idempotency against SMS redelivery.
 * A self-transfer's debit and credit legs share ONE RRN but differ in `direction`, so both survive.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["rrn", "direction"], unique = true),
        Index(value = ["timestampEvent"]),
        Index(value = ["direction", "timestampEvent"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,            // UUIDv7
    val amountPaise: Long,
    val direction: Direction,
    val status: TxnStatus,
    val payeeName: String? = null,
    val payeeVpa: String? = null,
    val payerAccountLast4: String? = null,
    val bankLabel: String? = null,
    val rrn: String? = null,               // 12-digit UPI RRN (SMS only); null for a11y-only rows
    val timestampEvent: Long,              // pay-time (a11y) or message time (SMS)
    val timestampCaptured: Long,           // when this app saw it
    val source: String,                    // Source.A11Y | SMS | A11Y_SMS
    val episodeId: String? = null,
    val needsReview: Boolean = false,
    // Categorization is a later phase; columns reserved so no migration is needed then.
    val category: String? = null,
    val categoryConfidence: Float? = null,
    val categorySource: String? = null,
    // Insights map (Slice C): where the payer physically was at pay-time, rounded to ~110 m. Null for
    // SMS/manual rows, opt-out, or no fresh+accurate fix. Going-forward-only; never uploaded.
    val latRounded: Double? = null,
    val lngRounded: Double? = null,
)
