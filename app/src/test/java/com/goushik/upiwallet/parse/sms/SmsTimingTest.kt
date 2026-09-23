package com.goushik.upiwallet.parse.sms

import org.junit.Assert.assertEquals
import org.junit.Test

/** When an SMS happened, and where its screen capture can be. Literal clocks only. */
class SmsTimingTest {

    private val arrived = 1_790_000_000_000L
    private val lookback = 11 * 60_000L + 30_000L // Reconciler: 90 s window + 10 min margin

    @Test fun `the earliest part's network time is the event time`() {
        assertEquals(arrived - 5_000, SmsTiming.eventTime(listOf(arrived - 3_000, arrived - 5_000), arrived))
    }

    @Test fun `a text held overnight is dated when the bank sent it`() {
        val sent = arrived - 7 * 60 * 60_000L // 23:50 → delivered 06:50
        assertEquals(sent, SmsTiming.eventTime(listOf(sent), arrived))
    }

    @Test fun `implausible network times fall back to arrival`() {
        assertEquals("missing", arrived, SmsTiming.eventTime(emptyList(), arrived))
        assertEquals("zero", arrived, SmsTiming.eventTime(listOf(0L), arrived))
        assertEquals("stamped after it arrived", arrived, SmsTiming.eventTime(listOf(arrived + 64_000), arrived))
        assertEquals("older than two days", arrived, SmsTiming.eventTime(listOf(arrived - SmsTiming.MAX_LAG_MS - 1), arrived))
    }

    @Test fun `an on-time text searches exactly the pre-v2 window`() {
        val w = SmsTiming.matchWindows(eventAt = arrived - 3_000, arrivedAt = arrived, lookbackMs = lookback)
        assertEquals(listOf(SmsTiming.Window(arrived - lookback, arrived, arrived)), w)
    }

    @Test fun `a late text looks back from when it was sent first, then falls back to the arrival window`() {
        // A held text must not reach a newer same-amount capture near its arrival before its own one: that
        // capture belongs to the next payment, whose text would then find nothing and book it twice.
        val sent = arrived - 3 * 60 * 60_000L
        val w = SmsTiming.matchWindows(eventAt = sent, arrivedAt = arrived, lookbackMs = lookback)
        assertEquals(2, w.size)
        assertEquals(SmsTiming.Window(sent - lookback, sent + SmsTiming.CLOCK_SKEW_MS, sent), w[0])
        assertEquals(SmsTiming.Window(arrived - lookback, arrived, arrived), w[1])
    }

    // ── a whole-12 h / 24 h stamp is the sender's clock slipping, not a text held that long ───────────────

    private val hour = 60 * 60_000L

    @Test fun `a stamp exactly 12 hours early is an AM-PM slip and falls back to arrival`() {
        assertEquals(arrived, SmsTiming.eventTime(listOf(arrived - 12 * hour), arrived))
        // The corpus slips sit a few seconds either side of 12 h.
        assertEquals(arrived, SmsTiming.eventTime(listOf(arrived - 12 * hour - 14_000), arrived))
        assertEquals(arrived, SmsTiming.eventTime(listOf(arrived - 12 * hour + 60_000), arrived))
    }

    @Test fun `a stamp exactly 24 hours early is a date slip and falls back to arrival`() {
        assertEquals(arrived, SmsTiming.eventTime(listOf(arrived - 24 * hour + 30_000), arrived))
    }

    @Test fun `a genuine hold just outside the slip band keeps its sent time`() {
        val justOver12 = arrived - 12 * hour - SmsTiming.CLOCK_SKEW_MS - 60_000
        assertEquals(justOver12, SmsTiming.eventTime(listOf(justOver12), arrived))
        val elevenHours = arrived - 11 * hour
        assertEquals(elevenHours, SmsTiming.eventTime(listOf(elevenHours), arrived))
        val dayAndAHalfHour = arrived - 24 * hour - 30 * 60_000L
        assertEquals(dayAndAHalfHour, SmsTiming.eventTime(listOf(dayAndAHalfHour), arrived))
    }

    @Test fun `a slipped text searches only the arrival window, never a slot 12 hours back`() {
        val eventAt = SmsTiming.eventTime(listOf(arrived - 12 * hour), arrived)
        assertEquals(
            listOf(SmsTiming.Window(arrived - lookback, arrived, arrived)),
            SmsTiming.matchWindows(eventAt = eventAt, arrivedAt = arrived, lookbackMs = lookback),
        )
    }
}
