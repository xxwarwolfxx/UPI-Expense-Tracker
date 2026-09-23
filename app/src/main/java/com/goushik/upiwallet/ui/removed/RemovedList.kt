package com.goushik.upiwallet.ui.removed

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.review.RemovedPayments
import com.goushik.upiwallet.domain.review.SampleRows
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Pure helpers for the Removed payments screen — host-testable, no Android. */
object RemovedList {

    /** A removed payment is only "worth putting back" if nothing live already counts the same money. */
    const val TWIN_WINDOW_MS = 2 * 60 * 60 * 1000L

    /**
     * Removed rows the bank itself confirmed (a bank SMS with a reference number merged into them) AND that
     * have no live twin — the same amount and direction still counted within [TWIN_WINDOW_MS]. A confirmed
     * row with a live twin was a duplicate; putting it back would count the money twice, so it is not
     * flagged. Likewise a removed credit whose live other half is a same-amount "Transfer-to-self" debit
     * within the window: it is money moved between the owner's own accounts, and putting it back would read
     * as income. On the owner's ledger this is the 10 payments an old capture bug hid (the removed ₹20,000
     * credit leg of a 7 Sep self-transfer is correctly left out).
     *
     * Rows the demo-mode seeder wrote ([SampleRows]) are never flagged: its SMS fixtures carry invented
     * reference numbers, so without this the 31 sample rows removed from Review would read as payments the
     * bank confirmed and top the list, next to the real ones.
     */
    fun confirmedIds(removed: List<TransactionEntity>, all: List<TransactionEntity>): Set<String> {
        val live = all.filter { it.status != TxnStatus.DISCARDED }
        // Seed runs are recognised across every row, removed or not, so a partly put-back run still counts.
        val sample = SampleRows.find((all + removed).distinctBy { it.id }).mapTo(HashSet()) { it.id }
        return removed.asSequence()
            .filter { it.id !in sample }
            .filter { RemovedPayments.isBankConfirmed(it) }
            .filter { r ->
                live.none {
                    it.id != r.id && it.amountPaise == r.amountPaise && it.direction == r.direction &&
                        abs(it.timestampEvent - r.timestampEvent) <= TWIN_WINDOW_MS
                }
            }
            .filter { r -> !isSelfTransferLeg(r, live) }
            .map { it.id }
            .toSet()
    }

    /** [r] is the credit half of a transfer between the owner's own accounts whose debit half still counts. */
    private fun isSelfTransferLeg(r: TransactionEntity, live: List<TransactionEntity>): Boolean =
        r.direction == Direction.CREDIT && live.any {
            it.direction == Direction.DEBIT && it.amountPaise == r.amountPaise &&
                it.category == Category.SELF_TRANSFER.label &&
                abs(it.timestampEvent - r.timestampEvent) <= TWIN_WINDOW_MS
        }

    private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")
    private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    /** Month header: "September" this year, "December 2025" otherwise. */
    fun monthLabel(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val thisYear = Instant.ofEpochMilli(now).atZone(zone).year
        return then.format(if (then.year == thisYear) MONTH else MONTH_YEAR)
    }
}
