package com.goushik.upiwallet.capture

import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TTL ring that stops one payment being booked twice while its sheet re-renders. */
class RecentCapturesTest {

    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"

    @Test fun `the same amount within the window is suppressed`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 8_700, 1_000)
        assertTrue(r.seenRecently(gpay, 8_700, 5_000))
    }

    @Test fun `the same amount after the window is allowed again`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 8_700, 1_000)
        assertFalse("a genuine repeat payment must still be recorded", r.seenRecently(gpay, 8_700, 40_000))
    }

    @Test fun `an interleaved different amount no longer lets the repeat through`() {
        // The hole this class exists to close: with only ONE remembered episode, ₹100 → ₹250 → ₹100 inside
        // three seconds booked three rows, because ₹250 evicted the open ₹100 and nothing had resolved yet.
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 10_000, 1_000)
        r.remember(gpay, 25_000, 2_000)
        assertTrue("the second ₹100 is the same payment re-rendering", r.seenRecently(gpay, 10_000, 3_000))
    }

    @Test fun `apps do not shadow each other`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 8_700, 1_000)
        assertFalse("paying the same amount in another app is a different payment",
            r.seenRecently(phonepe, 8_700, 2_000))
    }

    @Test fun `the ring stays small and drops the oldest entries`() {
        val r = RecentCaptures(ttlMs = 30_000, capacity = 3)
        for (i in 1..5) r.remember(gpay, i * 100L, 1_000L + i)
        assertEquals(3, r.size(2_000))
        assertFalse("the oldest fell off", r.seenRecently(gpay, 100, 2_000))
        assertTrue("the newest is held", r.seenRecently(gpay, 500, 2_000))
    }

    @Test fun `expired entries are pruned, not merely ignored`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 8_700, 1_000)
        assertEquals(0, r.size(40_000))
    }

    // ── settle(): how an episode's outcome re-arms the ring ─────────────────────────────────────────
    // These replay the exact calls the service makes: remember() when the sheet qualifies, settle() when
    // a success/failure screen resolves the episode, seenRecently() when the next sheet qualifies.

    @Test fun `a failed payment retried 15 s later opens a new episode`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 50_000, 0)                                           // sheet: Pay ₹500
        r.settle(gpay, 50_000, 8_000, TxnStatus.DISCARDED, sheetStillShowing = false) // "Payment failed"
        assertFalse(
            "the retry is a new payment — the old full hold dropped it, and only a bank SMS could recover it",
            r.seenRecently(gpay, 50_000, 23_000),
        )
    }

    @Test fun `the failure screen re-rendering right away is still absorbed`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 50_000, 0)
        r.settle(gpay, 50_000, 8_000, TxnStatus.DISCARDED, sheetStillShowing = false)
        assertTrue(r.seenRecently(gpay, 50_000, 8_000 + RecentCaptures.FAILURE_HOLD_MS - 1))
        assertFalse(r.seenRecently(gpay, 50_000, 8_000 + RecentCaptures.FAILURE_HOLD_MS))
    }

    @Test fun `a success holds the amount for the full window — the success animation re-flashes the sheet`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 50_000, 0)
        r.settle(gpay, 50_000, 8_000, TxnStatus.CONFIRMED, sheetStillShowing = false)
        assertTrue(r.seenRecently(gpay, 50_000, 37_000))
        assertFalse(r.seenRecently(gpay, 50_000, 38_000))
    }

    @Test fun `a failure read off the still-showing sheet keeps the full hold`() {
        // If the resolving screen is itself the live pay sheet, a short hold would let that same sheet open
        // a fresh episode on its next re-render — one payment, a row per re-render.
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 50_000, 0)
        r.settle(gpay, 50_000, 8_000, TxnStatus.DISCARDED, sheetStillShowing = true)
        assertTrue(r.seenRecently(gpay, 50_000, 23_000))
    }

    @Test fun `forgetting one payment leaves the others held`() {
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 50_000, 0)
        r.remember(gpay, 8_700, 1_000)
        r.remember(phonepe, 50_000, 2_000)
        r.forget(gpay, 50_000)
        assertFalse(r.seenRecently(gpay, 50_000, 3_000))
        assertTrue("a different amount is untouched", r.seenRecently(gpay, 8_700, 3_000))
        assertTrue("the same amount in another app is untouched", r.seenRecently(phonepe, 50_000, 3_000))
    }

    @Test fun `a short hold expires even behind a longer, older one`() {
        // Holds differ per entry now, so expiry can't just trim from the front of the ring.
        val r = RecentCaptures(ttlMs = 30_000)
        r.remember(gpay, 8_700, 0)                         // full hold, until 30 s
        r.remember(gpay, 50_000, 1_000, forMs = 5_000)     // short hold, until 6 s
        assertEquals(1, r.size(10_000))
        assertTrue(r.seenRecently(gpay, 8_700, 10_000))
    }
}
