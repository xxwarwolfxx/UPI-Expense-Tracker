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
import com.goushik.upiwallet.parse.sms.LateAlertMatch
import com.goushik.upiwallet.parse.sms.SmsTiming
import com.goushik.upiwallet.util.Dbg
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
        // Debug builds only: a payee name and an amount are exactly what must never reach a release log.
        Dbg.d { "a11y PENDING id=$id amt=${parsed.amountPaise} payee=${parsed.payeeName}" }
        return id
    }

    /** Episode terminal: success token -> CONFIRMED, failure/cancel -> DISCARDED. Guarded: a row the
     *  bank's SMS has already claimed (rrn set) is never overwritten — screen-reads are heuristic,
     *  the SMS is proof, and the 3-minute terminal window overlaps the SMS arrival band. */
    suspend fun setStatus(txnId: String, status: TxnStatus) {
        val n = txnDao.setStatusIfUnclaimed(txnId, status, System.currentTimeMillis())
        if (n > 0) Dbg.d { "status $txnId -> $status" }
        else Dbg.d { "status $txnId -> $status SKIPPED (bank SMS already claimed the row)" }
    }

    /**
     * SMS path — runs inside the receiver's goAsync(), off the main thread, self-sufficient.
     *
     * [eventAt] is when the bank sent the text ([SmsTiming.eventTime]; defaults to now). It dates rows the
     * SMS creates, so a text held overnight lands on the day the money moved, and a text the network held
     * is first searched for around its send time, so it still finds its own screen capture instead of
     * booking the payment twice. An on-time text searches the arrival-based window only, unchanged
     * ([SmsTiming.matchWindows]). `timestampCaptured` stays the arrival time.
     */
    suspend fun onSms(
        parsed: ParsedTxn,
        rawBody: String,
        sender: String?,
        eventAt: Long = System.currentTimeMillis(),
    ): SmsOutcome {
        val now = System.currentTimeMillis()
        val sentAt = eventAt.coerceAtMost(now)
        val rawId = Ids.uuid7()
        rawDao.insert(RawEventEntity(rawId, null, Source.SMS, sender, parsed.direction.name, rawBody, now))

        return db.withTransaction {
            // Credits have no confirm sheet — immediately canonical, never wait on an a11y match.
            if (parsed.direction == Direction.CREDIT) {
                val id = insertStandalone(parsed, sentAt, now, TxnStatus.CONFIRMED)
                rawDao.attach(rawId, id)
                Dbg.d { "sms CREDIT standalone id=$id amt=${parsed.amountPaise} rrn=${parsed.rrn}" }
                return@withTransaction SmsOutcome.CREDIT_STANDALONE
            }

            val rrn = parsed.rrn
            if (rrn == null) {
                val id = insertStandalone(parsed, sentAt, now, TxnStatus.CONFIRMED)
                rawDao.attach(rawId, id)
                Dbg.w { "sms DEBIT no-RRN standalone id=$id amt=${parsed.amountPaise}" }
                return@withTransaction SmsOutcome.DEBIT_STANDALONE
            }

            // Idempotency: this exact (rrn, DEBIT) already recorded?
            txnDao.findByRrn(rrn, Direction.DEBIT)?.let { existing ->
                rawDao.attach(rawId, existing.id)
                Dbg.d { "sms DEBIT duplicate rrn=$rrn -> ${existing.id}" }
                return@withTransaction SmsOutcome.DUPLICATE
            }

            // One-sided: the SMS is always later than the a11y row. Live rows first, across every window (a
            // held text's send-time window leads), before any DISCARDED one is resurrected.
            val windows = SmsTiming.matchWindows(sentAt, now, MATCH_WINDOW_MS + MARGIN_MS)
            for (w in windows) {
                for (cand in txnDao.findPendingMatches(parsed.amountPaise, Direction.DEBIT, w.from, w.to, w.pivot)) {
                    val claimed = txnDao.claimAndMerge(
                        cand.id, rrn, TxnStatus.CONFIRMED,
                        parsed.payeeVpa, parsed.payerAccountLast4, parsed.bankLabel, now,
                    )
                    if (claimed > 0) {
                        rawDao.attach(rawId, cand.id)
                        Dbg.d { "sms DEBIT MERGED rrn=$rrn -> ${cand.id}" }
                        return@withTransaction SmsOutcome.MERGED
                    }
                }
            }
            // SMS is authoritative — flip a heuristically-DISCARDED match back to CONFIRMED.
            for (w in windows) {
                txnDao.findDiscardedMatch(parsed.amountPaise, Direction.DEBIT, w.from, w.to, w.pivot)?.let { disc ->
                    txnDao.overrideStatus(disc.id, TxnStatus.CONFIRMED, rrn)
                    rawDao.attach(rawId, disc.id)
                    Dbg.d { "sms DEBIT FLIPPED discarded -> ${disc.id} rrn=$rrn" }
                    return@withTransaction SmsOutcome.FLIPPED
                }
            }
            // A bank that SENT its alert late (its network stamp is on time, so the windows above only reach
            // minutes back): look hours back, but only at a screen row for the same payee from the same bank.
            val late = LateAlertMatch.window(sentAt)
            for (cand in txnDao.findPendingMatches(parsed.amountPaise, Direction.DEBIT, late.from, late.to, late.pivot)) {
                if (!lateAlertFits(parsed, cand)) continue
                val claimed = txnDao.claimAndMerge(
                    cand.id, rrn, TxnStatus.CONFIRMED,
                    parsed.payeeVpa, parsed.payerAccountLast4, parsed.bankLabel, now,
                )
                if (claimed > 0) {
                    rawDao.attach(rawId, cand.id)
                    Dbg.d { "sms DEBIT MERGED late alert rrn=$rrn -> ${cand.id} (${(sentAt - cand.timestampEvent) / 60_000} min)" }
                    return@withTransaction SmsOutcome.MERGED
                }
            }
            // No a11y row (app was killed at pay-time, or capture was off) — SMS stands alone.
            val id = insertStandalone(parsed, sentAt, now, TxnStatus.CONFIRMED)
            rawDao.attach(rawId, id)
            Dbg.d { "sms DEBIT standalone id=$id rrn=$rrn" }
            SmsOutcome.DEBIT_STANDALONE
        }
    }

    /** ReconcileWorker safety net: age PENDING rows past the window to UNCONFIRMED. */
    suspend fun sweepStalePending(): Int {
        val n = txnDao.ageOutPending(System.currentTimeMillis() - STALE_PENDING_MS)
        if (n > 0) Log.d(TAG, "swept $n stale PENDING -> UNCONFIRMED")
        return n
    }

    /** An SMS-only row: dated when the bank sent it ([eventAt]), stamped captured at arrival ([now]). */
    private suspend fun insertStandalone(parsed: ParsedTxn, eventAt: Long, now: Long, status: TxnStatus): String {
        val id = Ids.uuid7()
        txnDao.insert(
            TransactionEntity(
                id = id, amountPaise = parsed.amountPaise, direction = parsed.direction, status = status,
                payeeName = parsed.payeeName, payeeVpa = parsed.payeeVpa,
                payerAccountLast4 = parsed.payerAccountLast4, bankLabel = parsed.bankLabel, rrn = parsed.rrn,
                timestampEvent = eventAt, timestampCaptured = now, source = Source.SMS,
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

        /**
         * May a bank alert sent hours after a screen capture claim it ([LateAlertMatch])? Only for the same
         * payee, and never across banks: a screen row captured paying from SBI is not the HDFC debit the text
         * reports, whatever the payee (replayed on the owner's ledger, one HDFC ₹1 alert would otherwise take
         * an SBI ₹1 screen row from 82 min earlier). An unknown bank on either side does not block — the payee
         * rule already carries the match.
         */
        internal fun lateAlertFits(sms: ParsedTxn, row: TransactionEntity): Boolean =
            LateAlertMatch.samePayee(sms.payeeName, sms.payeeVpa, row.payeeName, row.payeeVpa) &&
                sameBank(sms.bankLabel, row.bankLabel)

        private fun sameBank(a: String?, b: String?): Boolean =
            a == null || b == null || AccountMatch.normalizeBank(a) == AccountMatch.normalizeBank(b)
    }
}
