package com.goushik.upiwallet.parse.sms

import kotlin.math.abs

/**
 * When a bank SMS "happened", and where to look for the screen capture it confirms. Pure — the receiver
 * and the Reconciler call it with literal clocks, so the rules are host-testable.
 *
 * Why not simply the arrival time: a text held by the network (phone off overnight, festival congestion)
 * used to be dated at arrival — a 23:50 debit delivered at 07:00 lands on the wrong day, even the wrong
 * month — and it arrived too late to merge with its own screen capture, so the payment was booked twice.
 *
 * Why not simply the network's timestamp either: it is the operator's clock, not the phone's. In the
 * owner's 11,356 timestamped bank texts the median lag is ~3 s, but 186 are stamped AFTER they arrived
 * (by up to 15 days), and a month of SBI UPI alerts (Dec 2022 - Jan 2023) is off by exactly 12 hours —
 * an AM/PM slip at the sender. So the sent time is trusted only when it is plausible.
 */
object SmsTiming {
    /** Longest believable delivery lag; the corpus holds genuine ~24 h holds (a phone off for a day). */
    const val MAX_LAG_MS = 2 * 24 * 60 * 60 * 1000L

    /** Operator and phone clocks disagree by seconds; allow a little either way around the sent time. */
    const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    private const val HOUR_MS = 60 * 60 * 1000L

    /**
     * Lags that are a clock slip at the sender, not a delivery delay: exactly 12 h (the AM/PM slip — all 14
     * slipped SBI stamps in the corpus sit within seconds of it) or exactly 24 h (a date slip). A genuine
     * hold landing within [CLOCK_SKEW_MS] of either is far rarer than the slip; the corpus has none.
     */
    private val SLIPS_MS = listOf(12 * HOUR_MS, 24 * HOUR_MS)

    /**
     * The event time for an SMS: the earliest part's network timestamp when it is plausible (positive, not
     * after arrival, not older than [MAX_LAG_MS], and not a whole 12 h / 24 h clock slip), otherwise the
     * arrival time.
     */
    fun eventTime(partSentAtMillis: List<Long>, arrivedAt: Long): Long {
        val sent = partSentAtMillis.filter { it > 0 }.minOrNull() ?: return arrivedAt
        val lag = arrivedAt - sent
        if (sent > arrivedAt || lag > MAX_LAG_MS) return arrivedAt
        if (SLIPS_MS.any { abs(lag - it) <= CLOCK_SKEW_MS }) return arrivedAt
        return sent
    }

    /** One place to look for the screen row an SMS confirms: rows timed in [from, to], nearest [pivot]. */
    data class Window(val from: Long, val to: Long, val pivot: Long)

    /**
     * Where to look, in order. An on-time text gets exactly the pre-v2 rule — one window, one-sided, ending
     * at arrival (the phone's own clock, the same clock that timed the screen row) and reaching
     * [lookbackMs] back — so nothing that merged before merges differently now.
     *
     * A LATE text (sent more than [CLOCK_SKEW_MS] before it arrived) is searched where its payment actually
     * sits FIRST: the same one-sided lookback measured from when the bank sent it (plus [CLOCK_SKEW_MS] of
     * slack). A trusted sent time means the payment cannot be later than that, so trying the arrival window
     * first could hand a held text a newer same-amount capture that belongs to the next payment — whose own
     * text would then find nothing and book it twice. The arrival window stays as the fallback.
     */
    fun matchWindows(eventAt: Long, arrivedAt: Long, lookbackMs: Long): List<Window> {
        val classic = Window(from = arrivedAt - lookbackMs, to = arrivedAt, pivot = arrivedAt)
        if (arrivedAt - eventAt <= CLOCK_SKEW_MS) return listOf(classic)
        return listOf(Window(from = eventAt - lookbackMs, to = eventAt + CLOCK_SKEW_MS, pivot = eventAt), classic)
    }
}
