package com.goushik.upiwallet.domain

import android.util.Log
import androidx.room.withTransaction
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.util.Ids

enum class SmsOutcome { MERGED, FLIPPED, CREDIT_STANDALONE, DEBIT_STANDALONE, DUPLICATE }

/**
 * Async dedup. The a11y row exists at pay-time; the SMS (with the RRN) arrives ~90s later, so this is
 * insert-now / merge-later, not a synchronous match. All merges run inside a single Room transaction
 * whose claim is compare-and-set (claimAndMerge re-checks rrn IS NULL), so the in-process call and the
 * WorkManager sweep can never double-apply.
 */
class Reconciler(private val db: AppDatabase) {
    private val txnDao = db.transactionDao()
    private val rawDao = db.rawEventDao()

    /** a11y confirm sheet: persist a PENDING debit row immediately (survives a mid-PIN process kill).
     *  [lat]/[lng] are the rounded pay-time fix for the opt-in Insights map — null when off/no fix. */
    suspend fun onConfirmSheet(
        parsed: ParsedTxn,
        rawPayload: String,
        episodeId: String,
        pkg: String?,
        lat: Double? = null,
        lng: Double? = null,
    ): String {
        val now = System.currentTimeMillis()
        val id = Ids.uuid7()
        txnDao.insert(
            TransactionEntity(
                id = id, amountPaise = parsed.amountPaise, direction = Direction.DEBIT,
                status = TxnStatus.PENDING, payeeName = parsed.payeeName, bankLabel = parsed.bankLabel,
                timestampEvent = now, timestampCaptured = now, source = Source.A11Y, episodeId = episodeId,
                latRounded = lat, lngRounded = lng,
            )
        )
        rawDao.insert(RawEventEntity(Ids.uuid7(), id, Source.A11Y, pkg, "confirm-sheet", rawPayload, now))
        Log.d(TAG, "a11y PENDING id=$id amt=${parsed.amountPaise} payee=${parsed.payeeName}")
        return id
    }

    /** Episode terminal: success token -> CONFIRMED, failure/cancel -> DISCARDED, timeout -> UNCONFIRMED. */
    suspend fun setStatus(txnId: String, status: TxnStatus) {
        txnDao.byId(txnId)?.let {
            txnDao.update(it.copy(status = status, timestampCaptured = System.currentTimeMillis()))
            Log.d(TAG, "status $txnId -> $status")
        }
    }

    /** SMS path — runs inside the receiver's goAsync(), off the main thread, self-sufficient. */
    suspend fun onSms(parsed: ParsedTxn, rawBody: String, sender: String?): SmsOutcome {
        val now = System.currentTimeMillis()
        val rawId = Ids.uuid7()
        rawDao.insert(RawEventEntity(rawId, null, Source.SMS, sender, parsed.direction.name, rawBody, now))

        return db.withTransaction {
            // Credits have no confirm sheet — immediately canonical, never wait on an a11y match.
            if (parsed.direction == Direction.CREDIT) {
                val id = insertStandalone(parsed, now, TxnStatus.CONFIRMED)
                rawDao.attach(rawId, id)
                Log.d(TAG, "sms CREDIT standalone id=$id amt=${parsed.amountPaise} rrn=${parsed.rrn}")
                return@withTransaction SmsOutcome.CREDIT_STANDALONE
            }

            val rrn = parsed.rrn
            if (rrn == null) {
                val id = insertStandalone(parsed, now, TxnStatus.CONFIRMED)
                rawDao.attach(rawId, id)
                Log.w(TAG, "sms DEBIT no-RRN standalone id=$id amt=${parsed.amountPaise}")
                return@withTransaction SmsOutcome.DEBIT_STANDALONE
            }

            // Idempotency: this exact (rrn, DEBIT) already recorded?
            txnDao.findByRrn(rrn, Direction.DEBIT)?.let { existing ->
                rawDao.attach(rawId, existing.id)
                Log.d(TAG, "sms DEBIT duplicate rrn=$rrn -> ${existing.id}")
                return@withTransaction SmsOutcome.DUPLICATE
            }

            val from = now - MATCH_WINDOW_MS - MARGIN_MS  // one-sided: SMS is always later than the a11y row
            for (cand in txnDao.findPendingMatches(parsed.amountPaise, Direction.DEBIT, from, now, now)) {
                val claimed = txnDao.claimAndMerge(
                    cand.id, rrn, TxnStatus.CONFIRMED,
                    parsed.payeeVpa, parsed.payerAccountLast4, parsed.bankLabel, now,
                )
                if (claimed > 0) {
                    rawDao.attach(rawId, cand.id)
                    Log.d(TAG, "sms DEBIT MERGED rrn=$rrn -> ${cand.id}")
                    return@withTransaction SmsOutcome.MERGED
                }
            }
            // SMS is authoritative — flip a heuristically-DISCARDED match back to CONFIRMED.
            txnDao.findDiscardedMatch(parsed.amountPaise, Direction.DEBIT, from, now, now)?.let { disc ->
                txnDao.overrideStatus(disc.id, TxnStatus.CONFIRMED, rrn)
                rawDao.attach(rawId, disc.id)
                Log.d(TAG, "sms DEBIT FLIPPED discarded -> ${disc.id} rrn=$rrn")
                return@withTransaction SmsOutcome.FLIPPED
            }
            // No a11y row (app was killed at pay-time) — SMS stands alone.
            val id = insertStandalone(parsed, now, TxnStatus.CONFIRMED)
            rawDao.attach(rawId, id)
            Log.d(TAG, "sms DEBIT standalone id=$id rrn=$rrn")
            SmsOutcome.DEBIT_STANDALONE
        }
    }

    /** ReconcileWorker safety net: age PENDING rows past the window to UNCONFIRMED. */
    suspend fun sweepStalePending(): Int {
        val n = txnDao.ageOutPending(System.currentTimeMillis() - STALE_PENDING_MS)
        if (n > 0) Log.d(TAG, "swept $n stale PENDING -> UNCONFIRMED")
        return n
    }

    private suspend fun insertStandalone(parsed: ParsedTxn, now: Long, status: TxnStatus): String {
        val id = Ids.uuid7()
        txnDao.insert(
            TransactionEntity(
                id = id, amountPaise = parsed.amountPaise, direction = parsed.direction, status = status,
                payeeName = parsed.payeeName, payeeVpa = parsed.payeeVpa,
                payerAccountLast4 = parsed.payerAccountLast4, bankLabel = parsed.bankLabel, rrn = parsed.rrn,
                timestampEvent = now, timestampCaptured = now, source = Source.SMS,
            )
        )
        return id
    }

    companion object {
        const val TAG = "UpiWallet"
        const val MATCH_WINDOW_MS = 90_000L
        // Generous one-sided lookback so a bank SMS that runs minutes late still merges instead of
        // inserting a second CONFIRMED row (double-count). Aged-to-UNCONFIRMED a11y rows stay mergeable.
        const val MARGIN_MS = 10 * 60_000L
        const val STALE_PENDING_MS = 5 * 60_000L
    }
}
