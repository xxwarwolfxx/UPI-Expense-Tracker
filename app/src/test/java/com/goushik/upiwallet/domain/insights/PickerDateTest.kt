package com.goushik.upiwallet.domain.insights

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * PURE host tests for [PickerDate] and the CUSTOM window built from it. The Material3 date picker speaks
 * in 00:00 UTC of the picked day; reading that in the device zone put every custom range and manual date
 * one day early west of UTC, and pre-selected yesterday in India before 05:30. Every case pins an explicit
 * zone so the result can't depend on the machine running the test.
 */
class PickerDateTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val savedDefault: TimeZone = TimeZone.getDefault()

    @After fun restoreDefaultZone() = TimeZone.setDefault(savedDefault)

    private fun pick(y: Int, m: Int, d: Int) = PickerDate.of(LocalDate.of(y, m, d))
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    // ── the day a picker value stands for ──

    @Test fun `a picker value is its UTC calendar day, whatever the zone`() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        assertEquals(LocalDate.of(2026, 9, 10), PickerDate.toLocalDate(pick(2026, 9, 10)))
    }

    @Test fun `today's picker value in India just after midnight is today, not yesterday`() {
        val halfPastMidnight = at(kolkata, 2026, 9, 23, 0, 30)   // still 22 Sep in UTC
        assertEquals(pick(2026, 9, 23), PickerDate.today(halfPastMidnight, kolkata))
    }

    @Test fun `today's picker value late evening in New York is still today`() {
        val lateEvening = at(newYork, 2026, 9, 23, 23, 30)       // already 24 Sep in UTC
        assertEquals(pick(2026, 9, 23), PickerDate.today(lateEvening, newYork))
    }

    // ── nothing after today can be picked ──

    @Test fun `today and earlier are selectable, tomorrow is not`() {
        val now = at(kolkata, 2026, 9, 23, 0, 30)
        assertTrue(PickerDate.isOnOrBeforeToday(pick(2026, 9, 23), now, kolkata))
        assertTrue(PickerDate.isOnOrBeforeToday(pick(2026, 9, 1), now, kolkata))
        assertFalse(PickerDate.isOnOrBeforeToday(pick(2026, 9, 24), now, kolkata))
    }

    @Test fun `in New York late at night, the UTC date having turned does not unlock tomorrow`() {
        val now = at(newYork, 2026, 9, 23, 23, 30)
        assertTrue(PickerDate.isOnOrBeforeToday(pick(2026, 9, 23), now, newYork))
        assertFalse(PickerDate.isOnOrBeforeToday(pick(2026, 9, 24), now, newYork))
    }

    // ── a manual entry's timestamp ──

    @Test fun `no pick means now`() {
        val now = at(kolkata, 2026, 9, 23, 14, 15)
        assertEquals(now, PickerDate.eventMsFor(null, now, kolkata))
    }

    @Test fun `picking today gives now exactly`() {
        val now = at(kolkata, 2026, 9, 23, 0, 30)
        assertEquals(now, PickerDate.eventMsFor(PickerDate.today(now, kolkata), now, kolkata))
    }

    @Test fun `a picked day in New York is recorded on that day, at the current time of day`() {
        val now = at(newYork, 2026, 9, 23, 14, 15)
        assertEquals(at(newYork, 2026, 9, 10, 14, 15), PickerDate.eventMsFor(pick(2026, 9, 10), now, newYork))
    }

    // ── the CUSTOM window ──

    @Test fun `a custom range in New York covers exactly the picked days`() {
        val now = at(newYork, 2026, 9, 23, 12)
        val win = spendWindow(InsightsPeriod.CUSTOM, now, pick(2026, 9, 10), pick(2026, 9, 15), newYork)
        assertEquals(at(newYork, 2026, 9, 10, 0), win.startMs)
        assertEquals(at(newYork, 2026, 9, 16, 0), win.endMs)
        assertFalse(win.contains(at(newYork, 2026, 9, 9, 21)))    // the day before stays out
        assertTrue(win.contains(at(newYork, 2026, 9, 15, 23)))    // the picked end day is in
    }

    @Test fun `a custom range in India covers exactly the picked days`() {
        val now = at(kolkata, 2026, 9, 23, 12)
        val win = spendWindow(InsightsPeriod.CUSTOM, now, pick(2026, 9, 10), pick(2026, 9, 15), kolkata)
        assertEquals(at(kolkata, 2026, 9, 10, 0), win.startMs)
        assertEquals(at(kolkata, 2026, 9, 16, 0), win.endMs)
    }

    @Test fun `a reversed custom pair is the same window`() {
        val now = at(kolkata, 2026, 9, 23, 12)
        assertEquals(
            spendWindow(InsightsPeriod.CUSTOM, now, pick(2026, 9, 10), pick(2026, 9, 15), kolkata),
            spendWindow(InsightsPeriod.CUSTOM, now, pick(2026, 9, 15), pick(2026, 9, 10), kolkata),
        )
    }

    @Test fun `the Insights custom chart in New York labels and sums the picked days`() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val now = at(newYork, 2026, 9, 23, 12)
        val txns = listOf(
            debit("before", 1_000, at(newYork, 2026, 9, 9, 21)),
            debit("first", 2_000, at(newYork, 2026, 9, 10, 9)),
            debit("last", 4_000, at(newYork, 2026, 9, 15, 22)),
        )
        val data = bucketSpend(
            txns, emptySet(), emptySet(), InsightsPeriod.CUSTOM, now, pick(2026, 9, 10), pick(2026, 9, 15),
        )
        assertEquals("10 Sep – 15 Sep", data.rangeLabel)
        assertEquals(6_000L, data.totalPaise)
        assertEquals(2, data.txnCount)
        assertEquals(6, data.points.size)                          // one bar per picked day
    }

    private fun debit(id: String, paise: Long, ts: Long) = TransactionEntity(
        id = id, amountPaise = paise, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
        payeeName = "ACME Stores", timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )
}
