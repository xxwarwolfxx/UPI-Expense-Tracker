package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [SampleRows] — recognising the developer seeder's rows, and ONLY those. The
 * seeded rows here are built from [SampleData.FINGERPRINTS] exactly the way [SampleData.seed] writes them.
 */
class SampleRowsTest {

    private val seedNow = 1_780_000_000_000L

    /** What one seed run writes, as [SampleData.seed] does: event = now − offset, the fixture's own RRN. */
    private fun seedRun(now: Long = seedNow, idPrefix: String = "s") =
        SampleData.FINGERPRINTS.mapIndexed { i, fp ->
            TransactionEntity(
                id = "$idPrefix$i", amountPaise = fp.amountPaise, direction = fp.direction,
                status = TxnStatus.CONFIRMED, payeeName = fp.payeeName, rrn = fp.rrn,
                timestampEvent = now - fp.offsetMs, timestampCaptured = now - fp.offsetMs, source = fp.source,
            )
        }

    @Test fun `finds every row of a seed run`() {
        val run = seedRun()
        assertEquals(SampleData.FINGERPRINTS.size, SampleRows.find(run).size)
    }

    @Test fun `finds them whatever their status - a removed seeded row is still seeded`() {
        val run = seedRun().mapIndexed { i, t ->
            when (i % 3) {
                0 -> t.copy(status = TxnStatus.DISCARDED)
                1 -> t.copy(status = TxnStatus.UNCONFIRMED, category = "Food", needsReview = false)
                else -> t
            }
        }
        assertEquals(run.size, SampleRows.find(run).size)
    }

    @Test fun `real payments to the same merchant names are never matched`() {
        val real = listOf(
            // same name, different amount
            real("r1", "SWIGGY", 53_300, Source.A11Y_SMS, rrn = "512345678901"),
            real("r2", "BLINKIT", 21_100, Source.A11Y),
            // same name + amount, but a real bank reference instead of the seeder's made-up one
            real("r3", "SWIGGY", 43_200, Source.SMS, rrn = "512345678902"),
            // title-case, as a payment screen shows it
            real("r4", "Blinkit", 28_700, Source.A11Y),
        )
        val found = SampleRows.find(seedRun() + real)
        assertTrue(found.none { it.id.startsWith("r") })
        assertEquals(SampleData.FINGERPRINTS.size, found.size)
    }

    @Test fun `a lone exact look-alike is not enough - it must share the seed moment with others`() {
        // A real screen capture that happens to match a screen fixture exactly (name, amount, no RRN)…
        val fp = SampleData.FINGERPRINTS.first { it.source == Source.A11Y }
        val lookAlike = real("r1", fp.payeeName, fp.amountPaise, fp.source).copy(timestampEvent = 1_790_000_123_456L)
        assertTrue(SampleRows.find(listOf(lookAlike)).isEmpty())
        // …and alongside a genuine seed run it still stays out, because its date points to another moment.
        assertTrue(SampleRows.find(seedRun() + lookAlike).none { it.id == "r1" })
    }

    @Test fun `fewer than MIN_RUN fixtures on one moment are not a run`() {
        val two = seedRun().take(SampleRows.MIN_RUN - 1)
        assertTrue(SampleRows.find(two).isEmpty())
        val enough = seedRun().take(SampleRows.MIN_RUN)
        assertEquals(SampleRows.MIN_RUN, SampleRows.find(enough).size)
    }

    @Test fun `two separate seed runs are both found`() {
        val all = seedRun(seedNow, "a") + seedRun(seedNow + 86_400_000L * 3, "b")
        assertEquals(all.size, SampleRows.find(all).size)
    }

    @Test fun `a seeded row whose source or reference changed is not claimed`() {
        val run = seedRun()
        val smsIdx = SampleData.FINGERPRINTS.indexOfFirst { it.source == Source.SMS }
        val edited = run.toMutableList().apply {
            this[smsIdx] = this[smsIdx].copy(rrn = "512345678903", source = Source.A11Y_SMS)
        }
        val found = SampleRows.find(edited)
        assertEquals(run.size - 1, found.size)
        assertTrue(found.none { it.id == "s$smsIdx" })
    }

    @Test fun `the seeder's sample UPI IDs are spotted in a profile, case-insensitively`() {
        assertEquals(
            setOf("bramstoker@oksbi", "bramstoker@okhdfcbank"),
            SampleRows.sampleIdsIn(SampleData.SAMPLE_OWN_VPAS),
        )
        assertEquals(setOf("bramstoker@oksbi"), SampleRows.sampleIdsIn("ramesh@okaxis, BramStoker@OKSBI "))
        assertTrue(SampleRows.sampleIdsIn("ramesh@okaxis,ramesh@oksbi").isEmpty())
        assertTrue(SampleRows.sampleIdsIn("").isEmpty())
        assertTrue(SampleRows.sampleIdsIn(null).isEmpty())
    }

    private fun real(id: String, name: String, amount: Long, source: String, rrn: String? = null) = TransactionEntity(
        id = id, amountPaise = amount, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
        payeeName = name, rrn = rrn, timestampEvent = seedNow + 86_400_000L, timestampCaptured = seedNow + 86_400_000L,
        source = source,
    )
}
