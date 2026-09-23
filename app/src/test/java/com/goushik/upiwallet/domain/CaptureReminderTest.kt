package com.goushik.upiwallet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The nag cadence, with literal clocks. 5 h = 18_000_000 ms. */
class CaptureReminderTest {

    private val h = 60 * 60 * 1000L
    private val t0 = 1_718_452_800_000L // ~2024-06-15

    @Test fun `first sighting notifies instantly`() {
        assertEquals(ReminderAction.NOTIFY, CaptureReminder.decide(paused = true, pausedSince = 0L, lastNotifiedAt = 0L, now = t0))
    }

    @Test fun `inside the window stays quiet`() {
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0, now = t0 + 1 * h))
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0, now = t0 + 5 * h - 1))
    }

    @Test fun `repeats every five hours while still off`() {
        assertEquals(ReminderAction.NOTIFY, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0, now = t0 + 5 * h))
        // After the second nudge the clock restarts from THAT nudge, not from pausedSince.
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0 + 5 * h, now = t0 + 9 * h))
        assertEquals(ReminderAction.NOTIFY, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0 + 5 * h, now = t0 + 10 * h))
    }

    @Test fun `coming back on clears once and re-arms`() {
        assertEquals(ReminderAction.CLEAR, CaptureReminder.decide(false, pausedSince = t0, lastNotifiedAt = t0, now = t0 + 2 * h))
        // After the clear both stamps are 0 → healthy and quiet.
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(false, pausedSince = 0L, lastNotifiedAt = 0L, now = t0 + 3 * h))
        // The NEXT outage is instant again.
        assertEquals(ReminderAction.NOTIFY, CaptureReminder.decide(true, pausedSince = 0L, lastNotifiedAt = 0L, now = t0 + 4 * h))
    }

    @Test fun `healthy from the start never fires`() {
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(false, 0L, 0L, now = t0))
    }

    @Test fun `interval is five hours`() {
        assertEquals(5 * h, CaptureReminder.INTERVAL_MS)
    }

    // ── Before setup is finished (the Android 12 Welcome-screen alert) ─────────────────────────────

    @Test fun `a fresh install with capture off is not told to turn it back on`() {
        // Never switched on, nothing on record: stay silent, however long setup takes.
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(true, 0L, 0L, now = t0, onboarded = false))
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(true, 0L, 0L, now = t0 + 50 * h, onboarded = false))
    }

    @Test fun `leftover reminder state from before setup is cleared, not repeated`() {
        // An older build already posted one: clear it (and its install-time "since") instead of nagging on.
        assertEquals(ReminderAction.CLEAR, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = t0, now = t0 + 6 * h, onboarded = false))
        // Stamped but never posted (no permission yet) is still leftover state.
        assertEquals(ReminderAction.CLEAR, CaptureReminder.decide(true, pausedSince = t0, lastNotifiedAt = 0L, now = t0 + 1 * h, onboarded = false))
    }

    @Test fun `capture on during setup is quiet too`() {
        assertEquals(ReminderAction.NONE, CaptureReminder.decide(false, 0L, 0L, now = t0, onboarded = false))
    }

    @Test fun `once setup is done the first outage is instant`() {
        assertEquals(ReminderAction.NOTIFY, CaptureReminder.decide(true, 0L, 0L, now = t0, onboarded = true))
    }

    // ── Where an outage starts (the honest "since") ──────────────────────────────────────────────

    @Test fun `a switch-off the service reported is exact`() {
        val start = CaptureReminder.outageStart(serviceBound = false, unboundAt = t0, lastSeenOnAt = t0 - 3 * h, now = t0 + 60_000)
        assertEquals(OutageStart(t0, SinceKind.EXACT), start)
    }

    @Test fun `the unbind time wins even when a later tick is the first to notice`() {
        // onUnbind wrote the flag and its time, then the process died before its own tick ran.
        val start = CaptureReminder.outageStart(serviceBound = false, unboundAt = t0, lastSeenOnAt = t0 - 1 * h, now = t0 + 8 * h)
        assertEquals(OutageStart(t0, SinceKind.EXACT), start)
    }

    @Test fun `a force-stop noticed hours later says sometime after the last time capture was seen on`() {
        // Capture last seen on at 9:10, the app opened 33 h later: the start is 9:10 at the earliest, not "now".
        val seen = t0 + 9 * h + 10 * 60_000
        val start = CaptureReminder.outageStart(serviceBound = true, unboundAt = 0L, lastSeenOnAt = seen, now = seen + 33 * h)
        assertEquals(OutageStart(seen, SinceKind.AFTER), start)
    }

    @Test fun `a stale unbind time does not count once the service is bound`() {
        val start = CaptureReminder.outageStart(serviceBound = true, unboundAt = t0, lastSeenOnAt = t0 + 2 * h, now = t0 + 5 * h)
        assertEquals(OutageStart(t0 + 2 * h, SinceKind.AFTER), start)
    }

    @Test fun `never seen on means no time is claimed`() {
        val start = CaptureReminder.outageStart(serviceBound = true, unboundAt = 0L, lastSeenOnAt = 0L, now = t0)
        assertEquals(SinceKind.UNKNOWN, start.kind)
        // Non-zero, so the outage is on record and the 5-hour repeats run.
        assertEquals(t0, start.at)
    }

    @Test fun `a seen-on stamp from the future is not trusted`() {
        // The clock moved backwards: don't print a time that hasn't happened yet.
        val start = CaptureReminder.outageStart(serviceBound = true, unboundAt = 0L, lastSeenOnAt = t0 + 1 * h, now = t0)
        assertEquals(SinceKind.UNKNOWN, start.kind)
    }
}
