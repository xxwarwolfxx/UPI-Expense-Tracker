package com.goushik.upiwallet.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Display-time helpers for the wallet UI. Mirrors the `object Money` pattern. Device-local timezone;
 * uses java.time (safe at minSdk 31, no desugaring). Generalizes the old private `relTime` in
 * StatusScreen — see [ago] for the "checked Xm ago" form and [rowTime] for transaction-row times.
 */
object DateTime {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    private val DATE_YEAR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a")

    /** Clock time only: "2:14 PM" — for phrases that already carry the day ("since 2:14 PM"). */
    fun time(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(TIME_FMT)

    /** A plain day: "3 Jun", or "3 Jun 2025" when it isn't this year. Never "Today"/"Yesterday". */
    fun day(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val thisYear = Instant.ofEpochMilli(now).atZone(zone).year
        return then.format(if (then.year == thisYear) DATE_FMT else DATE_YEAR_FMT)
    }

    /** Full date+time for the transaction-detail screen: "Mon, 1 Jun 2026 · 2:14 PM". */
    fun full(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(FULL_FMT)

    /** Transaction-row time: "just now" / "12m ago" / "2:14 PM" / "Yesterday" / "12 May". */
    fun rowTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMs
        if (diff in 0 until 60_000) return "just now"
        if (diff in 0 until 3_600_000) return "${diff / 60_000}m ago"
        val z = zone
        val then = Instant.ofEpochMilli(epochMs).atZone(z)
        val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        return when (then.toLocalDate()) {
            today -> then.format(TIME_FMT)
            today.minusDays(1) -> "Yesterday"
            else -> then.format(DATE_FMT)
        }
    }

    /** Date-section header for a grouped transaction list: "Today" / "Yesterday" / "12 May" (this year) /
     *  "12 May 2025" (older). Rows sharing a calendar day fall under one header. */
    fun sectionLabel(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val z = zone
        val then = Instant.ofEpochMilli(epochMs).atZone(z).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        return when {
            then == today -> "Today"
            then == today.minusDays(1) -> "Yesterday"
            then.year == today.year -> then.format(DATE_FMT)
            else -> then.format(DATE_YEAR_FMT)
        }
    }

    /** Explicit date + time for the Place-sheet payment list: "14 Jun · 6:40 PM" (a spot's history can
     *  span months, so this never collapses to a relative "just now" like [rowTime]). */
    fun dayTime(epochMs: Long): String {
        val z = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "${z.format(DATE_FMT)} · ${z.format(TIME_FMT)}"
    }

    /** Relative "ago" form for status lines: "just now" / "12m ago" / "3h ago" / "2d ago". */
    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val d = now - epochMs
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000}m ago"
            d < 86_400_000 -> "${d / 3_600_000}h ago"
            else -> "${d / 86_400_000}d ago"
        }
    }

    /** Local start-of-month (1st, 00:00) as epoch ms — window boundary for the "This month" tile. */
    fun startOfMonthMs(now: Long = System.currentTimeMillis()): Long {
        val z = zone
        return Instant.ofEpochMilli(now).atZone(z).toLocalDate()
            .withDayOfMonth(1).atStartOfDay(z).toInstant().toEpochMilli()
    }

    /** Local start-of-day (00:00) as epoch ms — window boundary for the spend-only "Today" tile. */
    fun startOfDayMs(now: Long = System.currentTimeMillis()): Long {
        val z = zone
        return Instant.ofEpochMilli(now).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
    }

    /** Local start-of-week (Monday, 00:00) as epoch ms — Indian convention. */
    fun startOfWeekMs(now: Long = System.currentTimeMillis()): Long {
        val z = zone
        val date = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val backToMonday = (date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()
        return date.minusDays(backToMonday).atStartOfDay(z).toInstant().toEpochMilli()
    }

    /** Time-of-day greeting for the home top bar. */
    fun greeting(now: Long = System.currentTimeMillis()): String =
        when (Instant.ofEpochMilli(now).atZone(zone).hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
}
