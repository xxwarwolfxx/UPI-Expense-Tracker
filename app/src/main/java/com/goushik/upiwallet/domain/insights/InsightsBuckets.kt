package com.goushik.upiwallet.domain.insights

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.util.DateTime
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * PURE bucketing domain for the Insights chart. No Compose, no Android UI — only java.time + the
 * entity + the shared spend definitions. Unit-testable: every window is derived from [nowMs] (the
 * single clock), so the same inputs always yield the same buckets.
 */

enum class InsightsPeriod { DAY, WEEK, MONTH, YEAR, CUSTOM }

/** One x-axis bucket. amountPaise = total DEBIT spend (self-transfers excluded) inside the bucket.
 *  xLabel = the axis label to print under this bucket, or "" if this bucket prints no label (the chart
 *  prints only a sparse subset). */
data class ChartPoint(val bucketStartMs: Long, val amountPaise: Long, val xLabel: String)

data class InsightsData(
    val points: List<ChartPoint>,   // chronological; size >= 1 (a fully-empty range still returns zero-filled buckets)
    val totalPaise: Long,           // sum over the range
    val rangeLabel: String,         // human window label, e.g. "June 2026" / "This week" / "2026" / "12–18 Jun"
    val txnCount: Int,
)

/** PURE function — no Android deps beyond java.time + the entity. Reuses the SAME spend filter as
 *  HomeViewModel.spendSince (status != DISCARDED, direction == DEBIT, NOT self-transfer) and
 *  BalanceCalculator.isSelfTransfer for the self-transfer test. */
fun bucketSpend(
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    period: InsightsPeriod,
    nowMs: Long,
    customStartMs: Long? = null,
    customEndMs: Long? = null,
): InsightsData {
    val zone = ZoneId.systemDefault()
    val plan = buildPlan(period, nowMs, customStartMs, customEndMs, zone)

    // Spend set: the shared [isSpend] predicate ONLY (no >= cutoff here). ownVpas/ownNames are
    // forwarded as-is — the caller already normalizes them, exactly like HomeViewModel.
    val spend = txns.filter { isSpend(it, ownVpas, ownNames) }

    val n = plan.bucketStartsMs.size
    val amounts = LongArray(n)
    var count = 0
    var total = 0L

    for (t in spend) {
        // Membership is the half-open millis window; placement is the local-field index. Boundaries
        // are aligned, so the two agree.
        if (t.timestampEvent < plan.rangeStartMs || t.timestampEvent >= plan.rangeEndMs) continue
        val idx = plan.bucketIndexOf(t.timestampEvent)
        if (idx in 0 until n) {
            amounts[idx] += t.amountPaise
            total += t.amountPaise
            count++
        }
    }

    val points = (0 until n).map { i ->
        ChartPoint(
            bucketStartMs = plan.bucketStartsMs[i],
            amountPaise = amounts[i],
            xLabel = if (i in plan.labelIndices) plan.labelOf(i) else "",
        )
    }

    return InsightsData(
        points = points,
        totalPaise = total,
        rangeLabel = plan.rangeLabel,
        txnCount = count,
    )
}

/** One category's spend over the window. label == null → Uncategorized (its own slice). */
data class CategorySlice(val label: String?, val spentPaise: Long, val count: Int)

/**
 * PURE per-category rollup over the SAME [isSpend] predicate + [spendWindow] as [bucketSpend], so the
 * slices sum to the chart total *by construction* (unit-tested in CategoryRollupTest). Sorted
 * biggest-first; a null category becomes the Uncategorized slice.
 */
fun categoryRollup(
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    period: InsightsPeriod,
    nowMs: Long,
    customStartMs: Long? = null,
    customEndMs: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CategorySlice> {
    val win = spendWindow(period, nowMs, customStartMs, customEndMs, zone)
    return txns.asSequence()
        .filter { isSpend(it, ownVpas, ownNames) && win.contains(it.timestampEvent) }
        .groupBy { it.category }
        .map { (cat, list) -> CategorySlice(cat, list.sumOf { it.amountPaise }, list.size) }
        .sortedByDescending { it.spentPaise }
}

// ---------------------------------------------------------------------------------------------------
// Shared spend definitions — the SINGLE source of truth reused by the chart (above) AND the Insights
// map (MapProjection / the map's slice of InsightsViewModel). Keeping them here, used by buildPlan too,
// is what guarantees a map footnote ("N of M payments have a location") can never disagree with the
// chart's payment count: both count the same predicate over the same half-open window.
// ---------------------------------------------------------------------------------------------------

/** What counts as "spend" everywhere in Insights: not DISCARDED, a DEBIT, and not a self-transfer.
 *  ownVpas/ownNames are expected already-normalized (the VM forwards profile.ownVpaSet()/ownNameSet()). */
fun isSpend(txn: TransactionEntity, ownVpas: Set<String>, ownNames: Set<String>): Boolean =
    txn.status != TxnStatus.DISCARDED &&
        txn.direction == Direction.DEBIT &&
        !BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames)

/** The half-open spend window for a period: a payment counts iff startMs <= timestampEvent < endMs. */
data class SpendWindow(val startMs: Long, val endMs: Long) {
    fun contains(timestampEvent: Long): Boolean = timestampEvent in startMs until endMs
}

/**
 * The window for a period — the SAME boundaries [buildPlan] uses for its bucket grid (it derives its
 * range from this), so the map's membership test is identical to the chart's. WEEK/MONTH reuse
 * [DateTime.startOfWeekMs]/[DateTime.startOfMonthMs] (shared with Home); CUSTOM tolerates a reversed
 * pair and makes the picked end day inclusive (+1 day exclusive), falling back to the MONTH window
 * when either bound is missing.
 */
fun spendWindow(
    period: InsightsPeriod,
    nowMs: Long,
    customStartMs: Long? = null,
    customEndMs: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): SpendWindow = when (period) {
    InsightsPeriod.DAY -> {
        val d = localDate(nowMs, zone)
        SpendWindow(atDayStartMs(d, zone), atDayStartMs(d.plusDays(1), zone))
    }
    InsightsPeriod.WEEK -> {
        val start = DateTime.startOfWeekMs(nowMs)            // Monday 00:00
        SpendWindow(start, atDayStartMs(localDate(start, zone).plusDays(7), zone))
    }
    InsightsPeriod.MONTH -> {
        val start = DateTime.startOfMonthMs(nowMs)           // 1st 00:00
        SpendWindow(start, atDayStartMs(localDate(start, zone).plusMonths(1), zone))
    }
    InsightsPeriod.YEAR -> {
        val first = LocalDate.of(localDate(nowMs, zone).year, 1, 1)
        SpendWindow(atDayStartMs(first, zone), atDayStartMs(first.plusYears(1), zone))
    }
    InsightsPeriod.CUSTOM ->
        if (customStartMs == null || customEndMs == null) {
            spendWindow(InsightsPeriod.MONTH, nowMs, null, null, zone)
        } else {
            val loDate = localDate(minOf(customStartMs, customEndMs), zone)
            val hiDateExclusive = localDate(maxOf(customStartMs, customEndMs), zone).plusDays(1)
            SpendWindow(atDayStartMs(loDate, zone), atDayStartMs(hiDateExclusive, zone))
        }
}

// ---------------------------------------------------------------------------------------------------
// Internal plan: resolves the contiguous bucket grid, the index function, the sparse label set, and
// the human range label for a period. Everything below is pure.
// ---------------------------------------------------------------------------------------------------

private class BucketPlan(
    val bucketStartsMs: List<Long>,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val labelIndices: Set<Int>,
    val rangeLabel: String,
    val bucketIndexOf: (Long) -> Int,
    val labelOf: (Int) -> String,
)

private val DOW_FMT = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)   // Mon..Sun
private val MON_FMT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)   // Jan..Dec
private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DAY_MON_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun startOfDay(epochMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

private fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

private fun atDayStartMs(date: LocalDate, zone: ZoneId): Long =
    date.atStartOfDay(zone).toInstant().toEpochMilli()

private fun buildPlan(
    period: InsightsPeriod,
    nowMs: Long,
    customStartMs: Long?,
    customEndMs: Long?,
    zone: ZoneId,
): BucketPlan {
  // The grid's range IS the shared spend window — so chart membership and map membership are one test.
  val win = spendWindow(period, nowMs, customStartMs, customEndMs, zone)
  val start = win.startMs
  val end = win.endMs
  return when (period) {
    InsightsPeriod.DAY -> {
        val dayStart = localDate(start, zone)
        val dayStartZdt = dayStart.atStartOfDay(zone)
        val starts = (0 until 24).map { h -> dayStartZdt.plusHours(h.toLong()).toInstant().toEpochMilli() }
        val labels = mapOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p")
        BucketPlan(
            bucketStartsMs = starts,
            rangeStartMs = start,
            rangeEndMs = end,
            labelIndices = labels.keys,
            rangeLabel = "Today",
            // Index by ELAPSED hours off the day start (the same clock that builds `starts`), NOT the
            // wall-clock .hour — so membership and placement stay in lock-step even on a DST day.
            bucketIndexOf = { ms ->
                ChronoUnit.HOURS.between(dayStartZdt, Instant.ofEpochMilli(ms).atZone(zone)).toInt()
            },
            labelOf = { i -> labels[i] ?: "" },
        )
    }

    InsightsPeriod.WEEK -> {
        val startDate = localDate(start, zone)           // Monday (window start)
        val starts = (0 until 7).map { d -> atDayStartMs(startDate.plusDays(d.toLong()), zone) }
        BucketPlan(
            bucketStartsMs = starts,
            rangeStartMs = start,
            rangeEndMs = end,
            labelIndices = (0 until 7).toSet(),
            rangeLabel = "This week",
            bucketIndexOf = { ms -> ChronoUnit.DAYS.between(startDate, localDate(ms, zone)).toInt() },
            labelOf = { i -> startDate.plusDays(i.toLong()).format(DOW_FMT) },
        )
    }

    InsightsPeriod.MONTH -> {
        val startDate = localDate(start, zone)           // day 1 of the month (window start)
        val daysInMonth = startDate.lengthOfMonth()
        val starts = (0 until daysInMonth).map { d -> atDayStartMs(startDate.plusDays(d.toLong()), zone) }
        // Labels at day-of-month {1, 7, 14, 21, last} → indices {0, 6, 13, 20, daysInMonth-1}.
        val labelDays = listOf(1, 7, 14, 21, daysInMonth).filter { it in 1..daysInMonth }
        val labelIdx = labelDays.map { it - 1 }.toSet()
        BucketPlan(
            bucketStartsMs = starts,
            rangeStartMs = start,
            rangeEndMs = end,
            labelIndices = labelIdx,
            rangeLabel = startDate.format(MONTH_YEAR_FMT),   // "June 2026"
            bucketIndexOf = { ms -> localDate(ms, zone).dayOfMonth - 1 },
            labelOf = { i -> "%02d".format(i + 1) },          // zero-padded day-of-month
        )
    }

    InsightsPeriod.YEAR -> {
        val firstOfYear = localDate(start, zone)         // Jan 1 (window start)
        val starts = (0 until 12).map { m -> atDayStartMs(firstOfYear.plusMonths(m.toLong()), zone) }
        val labelIdx = setOf(0, 3, 6, 9, 11)                  // Jan, Apr, Jul, Oct, Dec
        BucketPlan(
            bucketStartsMs = starts,
            rangeStartMs = start,
            rangeEndMs = end,
            labelIndices = labelIdx,
            rangeLabel = firstOfYear.year.toString(),
            bucketIndexOf = { ms -> localDate(ms, zone).monthValue - 1 },
            labelOf = { i -> firstOfYear.plusMonths(i.toLong()).format(MON_FMT) },
        )
    }

    InsightsPeriod.CUSTOM -> {
        if (customStartMs == null || customEndMs == null) {
            buildPlan(InsightsPeriod.MONTH, nowMs, null, null, zone)
        } else {
            buildCustomPlan(customStartMs, customEndMs, nowMs, zone)
        }
    }
  }
}

/**
 * CUSTOM: [startOfDay(customStart) .. startOfDay(customEnd)+1day) so the picked end day is inclusive.
 * Adaptive granularity — span ≤ 62 days → daily buckets, else monthly. ~5 sparse, deduped labels.
 */
private fun buildCustomPlan(customStartMs: Long, customEndMs: Long, nowMs: Long, zone: ZoneId): BucketPlan {
    // Tolerate a reversed pair. Range comes from the shared window so it can't drift from the map.
    val loMs = minOf(customStartMs, customEndMs)
    val hiMs = maxOf(customStartMs, customEndMs)
    val startDate = localDate(loMs, zone)
    val endDateInclusive = localDate(hiMs, zone)
    val endExclusiveDate = endDateInclusive.plusDays(1)
    val win = spendWindow(InsightsPeriod.CUSTOM, nowMs, customStartMs, customEndMs, zone)
    val rangeStartMs = win.startMs
    val rangeEndMs = win.endMs
    val spanDays = ChronoUnit.DAYS.between(startDate, endExclusiveDate)   // inclusive day count

    val daily = spanDays <= 62

    val (starts, indexOf, labelOf) = if (daily) {
        val days = spanDays.toInt().coerceAtLeast(1)
        val s = (0 until days).map { d -> atDayStartMs(startDate.plusDays(d.toLong()), zone) }
        val idx: (Long) -> Int = { ms -> ChronoUnit.DAYS.between(startDate, localDate(ms, zone)).toInt() }
        val lbl: (Int) -> String = { i -> startDate.plusDays(i.toLong()).format(DAY_MON_FMT) }
        Triple(s, idx, lbl)
    } else {
        val startYm = YearMonth.from(startDate)
        val endYmInclusive = YearMonth.from(endDateInclusive)
        val months = (ChronoUnit.MONTHS.between(startYm, endYmInclusive) + 1).toInt().coerceAtLeast(1)
        val s = (0 until months).map { m ->
            atDayStartMs(startYm.plusMonths(m.toLong()).atDay(1), zone)
        }
        val idx: (Long) -> Int = { ms ->
            ChronoUnit.MONTHS.between(startYm, YearMonth.from(localDate(ms, zone))).toInt()
        }
        val lbl: (Int) -> String = { i -> startYm.plusMonths(i.toLong()).atDay(1).format(MON_FMT) }
        Triple(s, idx, lbl)
    }

    val n = starts.size
    // ~5 sparse labels: first, ~25%, ~50%, ~75%, last — deduped (they collide at small n).
    val labelIdx = listOf(
        0,
        Math.round(0.25 * (n - 1)).toInt(),
        Math.round(0.50 * (n - 1)).toInt(),
        Math.round(0.75 * (n - 1)).toInt(),
        n - 1,
    ).filter { it in 0 until n }.toSet()

    // Range label: "d MMM – d MMM"; drop the year when both dates fall in nowMs's year.
    val nowYear = localDate(nowMs, zone).year
    val sameCurrentYear = startDate.year == nowYear && endDateInclusive.year == nowYear
    val rangeLabel = if (sameCurrentYear) {
        "${startDate.format(DAY_MON_FMT)} – ${endDateInclusive.format(DAY_MON_FMT)}"
    } else {
        val withYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        "${startDate.format(withYear)} – ${endDateInclusive.format(withYear)}"
    }

    return BucketPlan(
        bucketStartsMs = starts,
        rangeStartMs = rangeStartMs,
        rangeEndMs = rangeEndMs,
        labelIndices = labelIdx,
        rangeLabel = rangeLabel,
        bucketIndexOf = indexOf,
        labelOf = labelOf,
    )
}
