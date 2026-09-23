package com.goushik.upiwallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words for the (upcoming) "Last payment recorded" line, with literal clocks. */
class CaptureLivenessTest {

    private val m = 60_000L
    private val h = 60 * m
    private val now = 1_718_452_800_000L

    @Test fun `nothing recorded yet`() {
        assertEquals("No payment recorded yet", CaptureLiveness(0L, 0, 0L).label(now))
    }

    @Test fun `last recorded reads as an age`() {
        assertEquals("Last payment recorded just now", CaptureLiveness(now - 20_000, 0, 0L).label(now))
        assertEquals("Last payment recorded 12m ago", CaptureLiveness(now - 12 * m, 0, 0L).label(now))
        assertEquals("Last payment recorded 3h ago", CaptureLiveness(now - 3 * h, 0, 0L).label(now))
        assertEquals("Last payment recorded 2d ago", CaptureLiveness(now - 50 * h, 0, 0L).label(now))
    }

    @Test fun `no unmatched bank payments means no second line`() {
        assertNull(CaptureLiveness(now - h, 0, 0L).unmatchedLabel())
    }

    @Test fun `unmatched bank payments are counted, singular and plural`() {
        assertEquals("1 bank payment since then wasn't seen on screen", CaptureLiveness(now - h, 1, now - m).unmatchedLabel())
        assertEquals("3 bank payments since then weren't seen on screen", CaptureLiveness(now - h, 3, now - m).unmatchedLabel())
    }

    @Test fun `with nothing recorded there is no then`() {
        assertEquals("2 bank payments so far weren't seen on screen", CaptureLiveness(0L, 2, now - m).unmatchedLabel())
    }
}
