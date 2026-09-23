package com.goushik.upiwallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Switched on" is not "running": the listed-but-unbound case seen on the Pixel after a process kill. */
class CapturePausedStateTest {

    private val t0 = 1_718_452_800_000L
    private val min = 60_000L

    @Test fun `bound and flagged on is running`() {
        val r = CaptureReminder.pausedState(bound = true, listed = true, serviceBoundFlag = true, listedUnboundSince = 0L, now = t0)
        assertFalse(r.paused); assertTrue(r.running); assertEquals(0L, r.listedUnboundSince)
    }

    @Test fun `an explicit unbind is paused at once`() {
        val r = CaptureReminder.pausedState(bound = false, listed = false, serviceBoundFlag = false, listedUnboundSince = 0L, now = t0)
        assertTrue(r.paused)
    }

    @Test fun `switched off in settings is paused at once`() {
        val r = CaptureReminder.pausedState(bound = false, listed = false, serviceBoundFlag = true, listedUnboundSince = 0L, now = t0)
        assertTrue(r.paused); assertFalse(r.running)
    }

    @Test fun `listed but not running starts a grace clock and is not paused yet`() {
        val r = CaptureReminder.pausedState(bound = false, listed = true, serviceBoundFlag = true, listedUnboundSince = 0L, now = t0)
        assertFalse(r.paused); assertFalse(r.running); assertEquals(t0, r.listedUnboundSince)
    }

    @Test fun `listed but not running for five minutes is paused`() {
        val within = CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0, now = t0 + 5 * min - 1)
        assertFalse(within.paused)
        val after = CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0, now = t0 + 5 * min)
        assertTrue(after.paused); assertEquals(t0, after.listedUnboundSince)
    }

    @Test fun `binding again clears the grace clock`() {
        val r = CaptureReminder.pausedState(bound = true, listed = true, serviceBoundFlag = true, listedUnboundSince = t0, now = t0 + 20 * min)
        assertFalse(r.paused); assertEquals(0L, r.listedUnboundSince)
    }

    @Test fun `a grace clock from the future is reset to now`() {
        val r = CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0 + 60 * min, now = t0)
        assertEquals(t0, r.listedUnboundSince); assertFalse(r.paused)
    }

    @Test fun `only the listed-but-not-running case counts as stuck`() {
        assertTrue(CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0, now = t0 + 6 * min).stuck)
        assertFalse(CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0, now = t0 + 1 * min).stuck)
        assertFalse(CaptureReminder.pausedState(false, false, true, 0L, t0).stuck)
        assertFalse(CaptureReminder.pausedState(false, false, false, 0L, t0).stuck)
        assertFalse(CaptureReminder.pausedState(true, true, true, 0L, t0).stuck)
    }

    // ── the follow-up booked when a grace clock starts, so "paused" lands on time ──

    private val margin = CaptureReminder.GRACE_FOLLOW_UP_MARGIN_MS

    @Test fun `a new grace clock books a follow-up for when it runs out`() {
        val r = CaptureReminder.pausedState(false, true, true, listedUnboundSince = 0L, now = t0)
        assertEquals(5 * min + margin, CaptureReminder.graceFollowUpDelayMs(previousSince = 0L, read = r, now = t0))
    }

    @Test fun `a clock already running books nothing more`() {
        val r = CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0, now = t0 + 2 * min)
        assertEquals(null, CaptureReminder.graceFollowUpDelayMs(previousSince = t0, read = r, now = t0 + 2 * min))
    }

    @Test fun `a clock restarted after the service came back and died again books a fresh follow-up`() {
        // Bound again at t0 + 2 min (clock cleared), then not running again at t0 + 3 min.
        val r = CaptureReminder.pausedState(false, true, true, listedUnboundSince = 0L, now = t0 + 3 * min)
        assertEquals(5 * min + margin, CaptureReminder.graceFollowUpDelayMs(0L, r, t0 + 3 * min))
    }

    @Test fun `a clock pulled back from the future books from now`() {
        val r = CaptureReminder.pausedState(false, true, true, listedUnboundSince = t0 + 60 * min, now = t0)
        assertEquals(5 * min + margin, CaptureReminder.graceFollowUpDelayMs(t0 + 60 * min, r, t0))
    }

    @Test fun `running, switched off or already paused books nothing`() {
        val running = CaptureReminder.pausedState(true, true, true, listedUnboundSince = t0, now = t0 + min)
        assertEquals(null, CaptureReminder.graceFollowUpDelayMs(t0, running, t0 + min))
        val off = CaptureReminder.pausedState(false, false, true, 0L, t0)
        assertEquals(null, CaptureReminder.graceFollowUpDelayMs(0L, off, t0))
        val unbound = CaptureReminder.pausedState(false, true, false, 0L, t0)
        assertEquals(null, CaptureReminder.graceFollowUpDelayMs(0L, unbound, t0))
        val zeroGrace = CaptureReminder.pausedState(false, true, true, 0L, t0, graceMs = 0L)
        assertTrue(zeroGrace.paused)
        assertEquals(null, CaptureReminder.graceFollowUpDelayMs(0L, zeroGrace, t0, graceMs = 0L))
    }
}
