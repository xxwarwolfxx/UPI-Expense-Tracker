package com.goushik.upiwallet.ui.removed

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RemovedListTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(m: Int, d: Int, h: Int = 12) = LocalDateTime.of(2026, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun txn(
        id: String, paise: Long, status: TxnStatus, ts: Long,
        source: String = "a11y+sms", rrn: String? = "111111111111", dir: Direction = Direction.DEBIT,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = dir, status = status, rrn = rrn,
        timestampEvent = ts, timestampCaptured = ts, source = source,
    )

    @Test fun `a bank-confirmed removed payment with nothing live counting it is flagged`() {
        val removed = txn("r1", 88_808, TxnStatus.DISCARDED, at(8, 10))
        assertEquals(setOf("r1"), RemovedList.confirmedIds(listOf(removed), listOf(removed)))
    }

    @Test fun `a removed screen-only payment is not bank confirmed`() {
        val removed = txn("r1", 5_000, TxnStatus.DISCARDED, at(8, 10), source = "a11y", rrn = null)
        assertEquals(emptySet<String>(), RemovedList.confirmedIds(listOf(removed), listOf(removed)))
    }

    @Test fun `a removed duplicate whose twin still counts is not flagged`() {
        val removed = txn("r1", 2_000_000, TxnStatus.DISCARDED, at(9, 7, 8), source = "sms", dir = Direction.CREDIT)
        val liveTwin = txn("l1", 2_000_000, TxnStatus.UNCONFIRMED, at(9, 7, 8) - 60_000, source = "a11y", rrn = null, dir = Direction.CREDIT)
        assertEquals(emptySet<String>(), RemovedList.confirmedIds(listOf(removed), listOf(removed, liveTwin)))
    }

    @Test fun `a same-amount live payment far away in time is not a twin`() {
        val removed = txn("r1", 25_000, TxnStatus.DISCARDED, at(9, 4, 9))
        val other = txn("l1", 25_000, TxnStatus.CONFIRMED, at(9, 5, 9))
        assertEquals(setOf("r1"), RemovedList.confirmedIds(listOf(removed), listOf(removed, other)))
    }

    @Test fun `removed demo-mode rows are never bank confirmed, even with their invented references`() {
        val t0 = at(6, 5)
        val seeded = SampleData.FINGERPRINTS.mapIndexed { i, fp ->
            TransactionEntity(
                id = "s$i", amountPaise = fp.amountPaise, direction = fp.direction, status = TxnStatus.DISCARDED,
                payeeName = fp.payeeName, rrn = fp.rrn, source = fp.source,
                timestampEvent = t0 - fp.offsetMs, timestampCaptured = t0 - fp.offsetMs,
            )
        }
        assertTrue("the fixtures do carry references", seeded.any { it.rrn != null })
        val real = txn("r", 30_000, TxnStatus.DISCARDED, at(9, 18))
        val removed = seeded + real
        assertEquals(setOf("r"), RemovedList.confirmedIds(removed, removed))

        // Put most of the run back: the two still removed are still recognised as demo rows.
        val partly = seeded.mapIndexed { i, t -> if (i < 2) t else t.copy(status = TxnStatus.CONFIRMED) }
        assertEquals(setOf("r"), RemovedList.confirmedIds(partly.take(2) + real, partly + real))
    }

    @Test fun `the removed credit half of a transfer to yourself is not flagged, a real credit is`() {
        val ts = at(9, 7, 8)
        val outLeg = txn("out", 1_234_500, TxnStatus.UNCONFIRMED, ts, source = "a11y", rrn = null)
            .copy(category = "Transfer-to-self")
        val inLeg = txn("in", 1_234_500, TxnStatus.DISCARDED, ts, source = "sms", dir = Direction.CREDIT)
        assertEquals(emptySet<String>(), RemovedList.confirmedIds(listOf(inLeg), listOf(outLeg, inLeg)))

        // The same credit next to an ordinary payment of that amount is real money in: still flagged.
        val paid = outLeg.copy(category = "Other")
        assertEquals(setOf("in"), RemovedList.confirmedIds(listOf(inLeg), listOf(paid, inLeg)))
    }

    @Test fun `months read without the year this year and with it otherwise`() {
        assertEquals("September", RemovedList.monthLabel(at(9, 18), at(9, 23), zone))
        assertEquals("December 2025", RemovedList.monthLabel(
            LocalDateTime.of(2025, 12, 3, 12, 0).atZone(zone).toInstant().toEpochMilli(), at(9, 23), zone,
        ))
    }
}
