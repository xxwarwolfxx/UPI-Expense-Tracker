package com.goushik.upiwallet.capture

import com.goushik.upiwallet.domain.OutageStart
import com.goushik.upiwallet.domain.SinceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The "since" phrase in the reminder — the fact that tells you which payments to enter by hand. */
class CaptureAlertsTest {

    private fun at(y: Int, m: Int, d: Int, hh: Int, mm: Int): Long =
        LocalDateTime.of(y, m, d, hh, mm).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `same day reads as a clock time`() {
        val since = at(2026, 9, 15, 14, 14); val now = at(2026, 9, 15, 18, 0)
        // "h:mm a" — the am/pm case is locale-dependent, so only the clock part is pinned.
        assertTrue(CaptureAlerts.sinceLabel(since, now).startsWith("since 2:14"))
    }

    @Test fun `yesterday says so`() {
        val since = at(2026, 9, 14, 23, 5); val now = at(2026, 9, 15, 9, 0)
        assertTrue(CaptureAlerts.sinceLabel(since, now).startsWith("since yesterday, 11:05"))
    }

    @Test fun `older carries the date`() {
        val since = at(2026, 9, 10, 8, 30); val now = at(2026, 9, 15, 9, 0)
        // "Sep" on Android, "Sept" on a desktop JVM — pin the day and the clock, not the month spelling.
        val label = CaptureAlerts.sinceLabel(since, now)
        assertTrue(label, label.startsWith("since 10 Sep") && label.contains(", 8:30"))
    }

    // ── The honest variants (exact switch-off vs. noticed later vs. never seen on) ─────────────────

    @Test fun `a lower bound reads as sometime after, with the same day forms`() {
        val now = at(2026, 9, 15, 18, 40)
        assertTrue(CaptureAlerts.afterLabel(at(2026, 9, 15, 9, 10), now).startsWith("sometime after 9:10"))
        assertTrue(CaptureAlerts.afterLabel(at(2026, 9, 14, 9, 10), now).startsWith("sometime after yesterday, 9:10"))
        val older = CaptureAlerts.afterLabel(at(2026, 9, 12, 9, 10), now)
        assertTrue(older, older.startsWith("sometime after 12 Sep") && older.contains(", 9:10"))
    }

    @Test fun `an exact switch-off keeps the shipped wording`() {
        val now = at(2026, 9, 15, 18, 0)
        val text = CaptureAlerts.bodyText(OutageStart(at(2026, 9, 15, 14, 14), SinceKind.EXACT), now, label)
        assertTrue(text, text.startsWith("Accessibility for UPI ET has been off since 2:14"))
        assertTrue(text, text.endsWith(". Tap to turn it back on."))
    }

    @Test fun `an outage noticed late never claims an exact since`() {
        // Force-stopped Monday morning, app opened Tuesday evening: say when it was last known on.
        val now = at(2026, 9, 15, 18, 40)
        val text = CaptureAlerts.bodyText(OutageStart(at(2026, 9, 14, 9, 10), SinceKind.AFTER), now, label)
        assertTrue(text, text.startsWith("Accessibility for UPI ET is off. It stopped sometime after yesterday, 9:10"))
        assertTrue(text, text.endsWith(". Tap to turn it back on."))
        assertFalse(text, text.contains("off since"))
    }

    @Test fun `with nothing to anchor on no time is shown`() {
        val now = at(2026, 9, 15, 18, 40)
        assertEquals(
            "Accessibility for UPI ET is off. Tap to turn it back on.",
            CaptureAlerts.bodyText(OutageStart(now, SinceKind.UNKNOWN), now, label),
        )
    }

    @Test fun `a stuck service asks for off-and-on, not turn it on`() {
        val now = at(2026, 9, 23, 18, 56)
        val text = CaptureAlerts.bodyText(OutageStart(at(2026, 9, 23, 18, 49), SinceKind.AFTER), now, label, stuck = true)
        assertTrue(text, text.startsWith("Capture stopped sometime after 6:49"))
        assertTrue(text, text.contains("even though Accessibility still shows it on"))
        assertTrue(text, text.endsWith("switch UPI Expense Tracker capture off and on again."))
        assertFalse(text, text.contains("turn it back on"))
        val unknown = CaptureAlerts.bodyText(OutageStart(now, SinceKind.UNKNOWN), now, label, stuck = true)
        assertTrue(unknown, unknown.startsWith("Capture has stopped, even though"))
        assertTrue(unknown, unknown.endsWith("switch UPI Expense Tracker capture off and on again."))
    }

    @Test fun `the stuck reminder names the switch exactly as the Accessibility list shows it`() {
        // The list shows the service's a11y_service_label; there is no "UPI ET capture" entry to find.
        assertEquals("UPI Expense Tracker capture", label)
        val now = at(2026, 9, 23, 18, 56)
        val text = CaptureAlerts.bodyText(OutageStart(now, SinceKind.UNKNOWN), now, label, stuck = true)
        assertFalse(text, text.contains("UPI ET capture"))
        // The test copy's reminder names the test copy's own switch.
        val testCopy = CaptureAlerts.bodyText(OutageStart(now, SinceKind.UNKNOWN), now, "UPI ET TEST COPY capture", stuck = true)
        assertTrue(testCopy, testCopy.endsWith("switch UPI ET TEST COPY capture off and on again."))
    }

    @Test fun `the posted title resource matches TITLE in the release build`() {
        // The notification posts R.string.capture_off_title (so the debug copy can read "TEST · …").
        // Keep the release resource and the constant the tests pin in step.
        assertEquals(CaptureAlerts.TITLE, releaseString("capture_off_title"))
    }

    /** The release build's service label, read from the resource the notification uses. */
    private val label: String get() = releaseString("a11y_service_label")

    private fun releaseString(name: String): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        var strings: java.io.File? = null
        repeat(6) {
            val f = java.io.File(dir, "src/main/res/values/strings.xml").takeIf { it.isFile }
                ?: java.io.File(dir, "app/src/main/res/values/strings.xml").takeIf { it.isFile }
            if (f != null && strings == null) strings = f
            dir = dir?.parentFile
        }
        val xml = requireNotNull(strings) { "strings.xml not found" }.readText()
        val value = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1]
        return value.replace("\\'", "'")
    }

    @Test fun `title is unchanged`() {
        assertEquals("Payments aren't being recorded", CaptureAlerts.TITLE)
    }
}
