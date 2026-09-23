package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [BackedOutDetection] — "Did this go through?" for a payment screen followed seconds
 * later by another payment screen in the same app. Synthetic rows only.
 */
class BackedOutDetectionTest {

    private val sec = 1_000L
    private val t0 = 1_000_000_000L
    private val later = t0 + 60 * 60 * sec          // "now": an hour after — well past the settle time
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"

    @Test fun `a typo corrected seven seconds later is flagged, with the follower and the gap`() {
        val typo = txn("a", 51_000, ts = t0)
        val real = txn("b", 21_000, ts = t0 + 7 * sec, status = TxnStatus.CONFIRMED, source = Source.A11Y_SMS, rrn = "111122223333")
        val flags = BackedOutDetection.flag(listOf(real, typo), mapOf("a" to gpay, "b" to gpay), later)
        assertEquals(listOf("a"), flags.map { it.row.id })
        assertEquals("b", flags.single().next.id)
        assertEquals(7 * sec, flags.single().gapMs)
        assertEquals(gpay, flags.single().appPkg)
    }

    @Test fun `a bank-confirmed row is never flagged, whatever follows it`() {
        val proven = txn("a", 51_000, ts = t0, status = TxnStatus.CONFIRMED, source = Source.A11Y_SMS, rrn = "111122223333")
        val provenNoMerge = txn("c", 51_000, ts = t0 + 20 * sec, status = TxnStatus.UNCONFIRMED, rrn = "444455556666")
        val next = txn("b", 21_000, ts = t0 + 7 * sec)
        val flags = BackedOutDetection.flag(
            listOf(proven, provenNoMerge, next, txn("d", 100, ts = t0 + 30 * sec)),
            mapOf("a" to gpay, "b" to gpay, "c" to gpay, "d" to gpay), later,
        )
        assertTrue(flags.none { it.row.rrn != null })
        assertTrue(flags.none { it.row.id == "a" || it.row.id == "c" })
    }

    @Test fun `a row a success screen confirmed is not flagged`() {
        val done = txn("a", 51_000, ts = t0, status = TxnStatus.CONFIRMED)
        val next = txn("b", 21_000, ts = t0 + 7 * sec)
        assertTrue(BackedOutDetection.flag(listOf(done, next), mapOf("a" to gpay, "b" to gpay), later).isEmpty())
    }

    @Test fun `PENDING and UNCONFIRMED rows are both candidates`() {
        val pending = txn("a", 51_000, ts = t0, status = TxnStatus.PENDING)
        val next = txn("b", 21_000, ts = t0 + 7 * sec)
        val flags = BackedOutDetection.flag(listOf(pending, next), mapOf("a" to gpay, "b" to gpay), later)
        assertEquals(listOf("a"), flags.map { it.row.id })
    }

    @Test fun `SMS-only and manual rows are never candidates or followers`() {
        val sms = txn("a", 51_000, ts = t0, source = Source.SMS, status = TxnStatus.CONFIRMED)
        val manual = txn("b", 51_000, ts = t0, source = Source.MANUAL, status = TxnStatus.CONFIRMED)
        val screen = txn("c", 21_000, ts = t0 - 10 * sec)
        val flags = BackedOutDetection.flag(listOf(sms, manual, screen), mapOf("c" to gpay), later)
        assertTrue(flags.isEmpty())
    }

    @Test fun `a follower from a different app does not count`() {
        val a = txn("a", 60_045, ts = t0)
        val b = txn("b", 60_045, ts = t0 + 30 * sec)
        assertTrue(BackedOutDetection.flag(listOf(a, b), mapOf("a" to phonepe, "b" to gpay), later).isEmpty())
    }

    @Test fun `the follower must start within the window, and strictly after`() {
        val a = txn("a", 51_000, ts = t0)
        val tooLate = txn("b", 21_000, ts = t0 + 61 * sec)
        val sameInstant = txn("c", 21_000, ts = t0)
        val apps = mapOf("a" to gpay, "b" to gpay, "c" to gpay)
        assertTrue(BackedOutDetection.flag(listOf(a, tooLate, sameInstant), apps, later).none { it.row.id == "a" })
        // exactly at the window edge still counts
        val edge = txn("d", 21_000, ts = t0 + 60 * sec)
        assertEquals(
            "d",
            BackedOutDetection.flag(listOf(a, edge), mapOf("a" to gpay, "d" to gpay), later).single().next.id,
        )
    }

    @Test fun `an earlier capture is not a follower`() {
        val before = txn("a", 21_000, ts = t0 - 7 * sec)
        val row = txn("b", 51_000, ts = t0)
        val flags = BackedOutDetection.flag(listOf(before, row), mapOf("a" to gpay, "b" to gpay), later)
        assertEquals(listOf("a"), flags.map { it.row.id })   // only the EARLIER one is asked about
    }

    @Test fun `a removed follower does not count`() {
        val a = txn("a", 51_000, ts = t0)
        val removed = txn("b", 21_000, ts = t0 + 7 * sec, status = TxnStatus.DISCARDED)
        assertTrue(BackedOutDetection.flag(listOf(a, removed), mapOf("a" to gpay, "b" to gpay), later).isEmpty())
    }

    @Test fun `a removed row is not asked about`() {
        val a = txn("a", 51_000, ts = t0, status = TxnStatus.DISCARDED)
        val b = txn("b", 21_000, ts = t0 + 7 * sec)
        assertTrue(BackedOutDetection.flag(listOf(a, b), mapOf("a" to gpay, "b" to gpay), later).isEmpty())
    }

    @Test fun `rows with no known app are left alone`() {
        val a = txn("a", 51_000, ts = t0)
        val b = txn("b", 21_000, ts = t0 + 7 * sec)
        assertTrue(BackedOutDetection.flag(listOf(a, b), mapOf("b" to gpay), later).isEmpty())
        assertTrue(BackedOutDetection.flag(listOf(a, b), emptyMap(), later).isEmpty())
    }

    @Test fun `too fresh to ask - the bank SMS may still come`() {
        val a = txn("a", 51_000, ts = t0)
        val b = txn("b", 21_000, ts = t0 + 7 * sec)
        val apps = mapOf("a" to gpay, "b" to gpay)
        assertTrue(BackedOutDetection.flag(listOf(a, b), apps, now = t0 + 2 * 60 * sec).isEmpty())
        assertEquals(1, BackedOutDetection.flag(listOf(a, b), apps, now = t0 + BackedOutDetection.SETTLE_MS).size)
    }

    @Test fun `a row answered Keep is not asked again`() {
        val a = txn("a", 51_000, ts = t0)
        val b = txn("b", 21_000, ts = t0 + 7 * sec)
        val apps = mapOf("a" to gpay, "b" to gpay)
        assertTrue(BackedOutDetection.flag(listOf(a, b), apps, later, kept = setOf("a")).isEmpty())
    }

    @Test fun `the same-amount retry counts, and a chain of three flags the first two, newest first`() {
        val a = txn("a", 21_100, ts = t0)
        val b = txn("b", 21_100, ts = t0 + 40 * sec)
        val c = txn("c", 21_100, ts = t0 + 80 * sec, status = TxnStatus.CONFIRMED)
        val apps = mapOf("a" to gpay, "b" to gpay, "c" to gpay)
        val flags = BackedOutDetection.flag(listOf(c, b, a), apps, later)
        assertEquals(listOf("b", "a"), flags.map { it.row.id })
        assertEquals(listOf("c", "b"), flags.map { it.next.id })
    }

    @Test fun `a different-app capture in between does not hide the same-app follower`() {
        val a = txn("a", 51_000, ts = t0)
        val other = txn("b", 9_900, ts = t0 + 5 * sec, status = TxnStatus.CONFIRMED)
        val sameApp = txn("c", 21_000, ts = t0 + 9 * sec, status = TxnStatus.CONFIRMED)
        val flags = BackedOutDetection.flag(listOf(a, other, sameApp), mapOf("a" to gpay, "b" to phonepe, "c" to gpay), later)
        assertEquals("c", flags.single().next.id)
    }

    private fun txn(
        id: String,
        amount: Long,
        ts: Long,
        status: TxnStatus = TxnStatus.UNCONFIRMED,
        source: String = Source.A11Y,
        rrn: String? = null,
    ) = TransactionEntity(
        id = id, amountPaise = amount, direction = Direction.DEBIT, status = status,
        payeeName = "Ramesh Kumar", rrn = rrn, timestampEvent = ts, timestampCaptured = ts, source = source,
    )
}
