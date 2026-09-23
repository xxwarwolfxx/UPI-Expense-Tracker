package com.goushik.upiwallet.ui.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PURE host tests for [buildReviewState] and [buildRemovedState]. Synthetic rows only. */
class ReviewStateTest {

    private val t0 = 1_780_000_000_000L
    private val now = t0 + 3_600_000L
    private val gpay = "com.google.android.apps.nbu.paisa.user"

    @Test fun `a backed-out screen becomes a card naming the app, the amount and the gap`() {
        val typo = txn("a", 51_000, t0)
        val real = txn("b", 21_000, t0 + 7_000, status = TxnStatus.CONFIRMED)
        val s = buildReviewState(listOf(real, typo), mapOf("a" to gpay, "b" to gpay), null, emptySet(), null, now)
        val card = s.backedOut.single()
        assertEquals("a", card.id)
        assertEquals("Google Pay", card.app)
        assertEquals(7L, card.gapSeconds)
        assertTrue(card.body.startsWith("₹510.00 to Ramesh Kumar, "))
        assertTrue(card.body.endsWith("another Google Pay payment was started 7s later."))
        assertEquals(1, s.count)
    }

    @Test fun `Keep takes the card away`() {
        val a = txn("a", 51_000, t0)
        val b = txn("b", 21_000, t0 + 7_000, status = TxnStatus.CONFIRMED)
        val s = buildReviewState(listOf(b, a), mapOf("a" to gpay, "b" to gpay), null, setOf("a"), null, now)
        assertTrue(s.backedOut.isEmpty())
        assertEquals(0, s.count)
    }

    @Test fun `a did-this-go-through card alone lights the nav dot`() {
        // No category question (a person payment is never flagged), so the card is the only open question.
        val typo = txn("a", 51_000, t0)
        val real = txn("b", 21_000, t0 + 7_000, status = TxnStatus.CONFIRMED)
        val s = buildReviewState(listOf(real, typo), mapOf("a" to gpay, "b" to gpay), null, emptySet(), null, now)
        assertTrue(s.items.isEmpty())
        assertEquals(1, s.backedOut.size)
        assertTrue(s.hasOpenQuestions)
    }

    @Test fun `the nav dot follows category questions too, and goes out when nothing is open`() {
        val flagged = txn("a", 9_900, t0, status = TxnStatus.CONFIRMED).copy(needsReview = true)
        assertTrue(buildReviewState(listOf(flagged), emptyMap(), null, emptySet(), null, now).hasOpenQuestions)

        // Answered: the card was kept, and the category question is gone.
        val typo = txn("b", 51_000, t0)
        val real = txn("c", 21_000, t0 + 7_000, status = TxnStatus.CONFIRMED)
        val settled = buildReviewState(
            listOf(real, typo, flagged.copy(needsReview = false)), mapOf("b" to gpay, "c" to gpay), null,
            kept = setOf("b"), undo = null, now = now,
        )
        assertFalse(settled.hasOpenQuestions)
        assertFalse(ReviewUiState.Empty.hasOpenQuestions)
    }

    @Test fun `category questions list live needsReview rows only`() {
        val flagged = txn("a", 9_900, t0, status = TxnStatus.CONFIRMED).copy(needsReview = true)
        val removed = txn("b", 9_900, t0 - 1, status = TxnStatus.DISCARDED).copy(needsReview = true)
        val s = buildReviewState(listOf(flagged, removed), emptyMap(), null, emptySet(), null, now)
        assertEquals(listOf("a"), s.items.map { it.id })
    }

    @Test fun `sample cleanup hooks - live seeded rows, their spend, and sample IDs in the profile`() {
        val seeded = seededRows()
        val profile = UserProfileEntity(displayName = "Juniper Quill", ownVpasCsv = SampleData.SAMPLE_OWN_VPAS, onboardedAt = t0)
        val s = buildReviewState(seeded.reversed(), emptyMap(), profile, emptySet(), null, now)
        assertEquals(seeded.size - 1, s.samples.count)
        assertTrue("the seeder's transfer to itself is one of the live rows", seeded.drop(1).any { it.id == selfTransferId(seeded) })
        // The seeder's ₹2,500 transfer to its own sample UPI ID is in no spend total while that ID is in the
        // profile, so it isn't part of what the samples add to spend either.
        val liveDebits = seeded.drop(1).filter { it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
        assertEquals(liveDebits - 250_000L, s.samples.liveDebitPaise)
        assertEquals(2, s.samples.sampleUpiIds.size)
        assertTrue(s.samples.needsAttention)
    }

    @Test fun `once the sample IDs leave the profile the seeder's transfer counts as spend too`() {
        val seeded = seededRows()
        val profile = UserProfileEntity(displayName = "Juniper Quill", ownVpasCsv = "juniper@okaxis", onboardedAt = t0)
        val s = buildReviewState(seeded.reversed(), emptyMap(), profile, emptySet(), null, now)
        val liveDebits = seeded.drop(1).filter { it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
        assertEquals(liveDebits, s.samples.liveDebitPaise)
        assertTrue(s.samples.sampleUpiIds.isEmpty())
    }

    @Test fun `the sample card lists the live rows newest first, their made-up income and their date span`() {
        val seeded = seededRows()
        val profile = UserProfileEntity(displayName = "Juniper Quill", ownVpasCsv = "juniper@okaxis", onboardedAt = t0)
        val s = buildReviewState(seeded.reversed(), emptyMap(), profile, emptySet(), null, now).samples
        val live = seeded.drop(1)
        assertEquals(live.size, s.rows.size)
        assertEquals(s.liveIds, s.rows.map { it.id })
        assertEquals(
            live.filter { it.direction == Direction.CREDIT }.sumOf { it.amountPaise },
            s.liveCreditPaise,
        )
        assertTrue("credits are counted", s.liveCreditPaise > 0)
        val first = com.goushik.upiwallet.util.DateTime.day(live.minOf { it.timestampEvent }, now)
        val last = com.goushik.upiwallet.util.DateTime.day(live.maxOf { it.timestampEvent }, now)
        assertEquals("$first – $last", s.rangeLabel)
        assertEquals("${live.size} sample payments from demo mode are in your history", s.rowsTitle)
    }

    @Test fun `a sample card alone lights the nav dot, and removing the rows and IDs puts it out`() {
        val seeded = seededRows()
        val demo = UserProfileEntity(displayName = "Juniper Quill", ownVpasCsv = SampleData.SAMPLE_OWN_VPAS, onboardedAt = t0)
        val open = buildReviewState(seeded, emptyMap(), demo, emptySet(), null, now)
        assertEquals(0, open.count)
        assertTrue(open.hasOpenQuestions)

        val real = demo.copy(ownVpasCsv = "juniper@okaxis")
        val cleaned = seeded.map { it.copy(status = TxnStatus.DISCARDED) }
        assertFalse(buildReviewState(cleaned, emptyMap(), real, emptySet(), null, now).hasOpenQuestions)
    }

    @Test fun `the demo-ID sentence is sized to how many IDs are the sample ones`() {
        val one = SampleCleanup(sampleUpiIds = setOf("a@oksbi"), ownUpiIdCount = 1)
        assertTrue(one.upiIdsBody.startsWith("The ID saved in your profile came from demo mode"))
        val both = SampleCleanup(sampleUpiIds = setOf("a@oksbi", "b@okhdfcbank"), ownUpiIdCount = 2)
        assertTrue(both.upiIdsBody.startsWith("Both IDs saved in your profile came from demo mode"))
        val some = SampleCleanup(sampleUpiIds = setOf("a@oksbi"), ownUpiIdCount = 3)
        assertTrue(some.upiIdsBody.startsWith("1 of the 3 IDs saved in your profile came from demo mode"))
        assertTrue(some.upiIdsBody.contains("may miss"))
    }

    @Test fun `the demo profile's two IDs read as both`() {
        val demo = UserProfileEntity(displayName = "Juniper Quill", ownVpasCsv = SampleData.SAMPLE_OWN_VPAS, onboardedAt = t0)
        val s = buildReviewState(emptyList(), emptyMap(), demo, emptySet(), null, now).samples
        assertTrue(s.upiIdsBody.startsWith("Both IDs"))
    }

    @Test fun `no sample rows and a real profile - nothing to clean up`() {
        val profile = UserProfileEntity(displayName = "Ramesh Kumar", ownVpasCsv = "ramesh@okaxis", onboardedAt = t0)
        val s = buildReviewState(listOf(txn("a", 100, t0)), emptyMap(), profile, emptySet(), null, now)
        assertTrue(!s.samples.needsAttention)
    }

    @Test fun `removed list marks the bank-confirmed rows`() {
        val proven = txn("a", 51_000, t0, status = TxnStatus.DISCARDED, source = Source.A11Y_SMS, rrn = "111122223333")
        val screenOnly = txn("b", 21_000, t0 - 1, status = TxnStatus.DISCARDED)
        val s = buildRemovedState(listOf(proven, screenOnly), null, now)
        assertEquals(listOf("a", "b"), s.rows.map { it.row.id })
        assertEquals(listOf(true, false), s.rows.map { it.bankConfirmed })
        assertEquals(1, s.bankConfirmedCount)
    }

    /** One seed run as the seeder writes it (the first row already removed). The fingerprint carries no UPI
     *  ID, so the seeder's transfer to its own sample ID gets that ID back here. */
    private fun seededRows(): List<TransactionEntity> = SampleData.FINGERPRINTS.mapIndexed { i, fp ->
        TransactionEntity(
            id = "s$i", amountPaise = fp.amountPaise, direction = fp.direction,
            status = if (i == 0) TxnStatus.DISCARDED else TxnStatus.CONFIRMED,
            payeeName = fp.payeeName, rrn = fp.rrn, source = fp.source,
            payeeVpa = if (fp.payeeName == "BRAM STOKER") "bramstoker@oksbi" else null,
            timestampEvent = t0 - fp.offsetMs, timestampCaptured = t0 - fp.offsetMs,
        )
    }

    private fun selfTransferId(rows: List<TransactionEntity>): String =
        rows.single { it.payeeVpa == "bramstoker@oksbi" && it.amountPaise == 250_000L }.id

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
