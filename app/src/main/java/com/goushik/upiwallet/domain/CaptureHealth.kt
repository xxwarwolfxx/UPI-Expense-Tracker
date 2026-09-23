package com.goushik.upiwallet.domain

import android.content.Context
import com.goushik.upiwallet.capture.A11yCaptureService
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Permissions

/**
 * A point-in-time read of the validated capture set. a11y is the PRIMARY capture path, so
 * [captureHealthy] keys on it; SMS + battery are reinforcing. This is the honest, Phase-0-validated
 * check — NOT research §11's 6 (which counted the dead notification-listener).
 */
data class HealthSnapshot(
    val a11yEnabled: Boolean,
    val smsGranted: Boolean,
    val batteryExempt: Boolean,
    val lastEventAt: Long,
    val checkedAt: Long,
) {
    val captureHealthy: Boolean get() = a11yEnabled
    val allGreen: Boolean get() = a11yEnabled && smsGranted && batteryExempt
}

object CaptureHealth {
    fun snapshot(ctx: Context, now: Long = System.currentTimeMillis()): HealthSnapshot =
        HealthSnapshot(
            a11yEnabled = Permissions.isA11yEnabled(ctx),
            smsGranted = Permissions.isSmsGranted(ctx),
            batteryExempt = Permissions.isBatteryUnrestricted(ctx),
            lastEventAt = A11yCaptureService.lastEventAt,
            checkedAt = now,
        )
}

/**
 * Lightweight persistence for the latest background health check (SharedPreferences — no Room
 * migration needed for a transient flag). The daily worker writes here; Settings' Capture health section
 * reads it to show "Checked 2h ago". It also holds the capture-paused reminder's state and the capture
 * liveness stamps (below).
 */
class HealthStore(ctx: Context) {
    private val prefs =
        ctx.applicationContext.getSharedPreferences("capture_health", Context.MODE_PRIVATE)

    fun save(s: HealthSnapshot) {
        prefs.edit()
            .putBoolean(KEY_A11Y, s.a11yEnabled)
            .putBoolean(KEY_SMS, s.smsGranted)
            .putBoolean(KEY_BATT, s.batteryExempt)
            .putLong(KEY_CHECKED, s.checkedAt)
            .apply()
    }

    fun lastCheckedAt(): Long = prefs.getLong(KEY_CHECKED, 0L)

    // ── Capture-paused reminder state (see CaptureWatch / CaptureReminder) ──────────────────────

    /**
     * Non-zero while an outage is on record (0 = capture is, as far as we know, on). It holds the outage's
     * start as [outageStart] reports it: exact, a lower bound, or (for UNKNOWN) just when it was noticed.
     */
    fun pausedSince(): Long = prefs.getLong(KEY_PAUSED_SINCE, 0L)

    /** When the reminder was last posted for this outage; 0 = never. */
    fun lastNotifiedAt(): Long = prefs.getLong(KEY_LAST_NOTIFIED, 0L)

    /**
     * The recorded outage, or null when there is none. An outage stored by an older build has no kind;
     * it was worded as an exact "off since", so it keeps that wording.
     */
    fun outageStart(): OutageStart? {
        val since = pausedSince()
        if (since == 0L) return null
        val kind = prefs.getString(KEY_PAUSED_KIND, null)
            ?.let { runCatching { SinceKind.valueOf(it) }.getOrNull() } ?: SinceKind.EXACT
        return OutageStart(since, kind)
    }

    fun markPaused(start: OutageStart, notifiedAt: Long) {
        prefs.edit()
            .putLong(KEY_PAUSED_SINCE, start.at)
            .putString(KEY_PAUSED_KIND, start.kind.name)
            .putLong(KEY_LAST_NOTIFIED, notifiedAt)
            .apply()
    }

    fun clearPaused() {
        prefs.edit().remove(KEY_PAUSED_SINCE).remove(KEY_PAUSED_KIND).remove(KEY_LAST_NOTIFIED).apply()
    }

    /**
     * The service's own word on whether it is bound. Defaults to TRUE (= "not known to be off"): it is set
     * false ONLY by `A11yCaptureService.onUnbind`, and back to true by `onServiceConnected`, so a
     * fresh install or a boot-time gap never reads as an outage.
     */
    fun serviceBound(): Boolean = prefs.getBoolean(KEY_SERVICE_BOUND, true)

    fun setServiceBound(bound: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_BOUND, bound).apply()
    }

    /** When the service was last unbound (switched off); 0 = not unbound since it last connected. */
    fun unboundAt(): Long = prefs.getLong(KEY_UNBOUND_AT, 0L)

    /**
     * The last time capture was seen ON (the service connecting, or a tick that found it on); 0 = never.
     * Persisted because the service's own timestamps die with the process, and a force-stop is exactly
     * the outage that kills the process. It is the honest lower bound for an outage noticed late.
     */
    fun lastSeenOnAt(): Long = prefs.getLong(KEY_LAST_SEEN_ON, 0L)

    /** When the service was first seen switched on in Settings but not running; 0 = not in that state. */
    fun listedUnboundSince(): Long = prefs.getLong(KEY_LISTED_UNBOUND_SINCE, 0L)

    fun setListedUnboundSince(at: Long) {
        prefs.edit().putLong(KEY_LISTED_UNBOUND_SINCE, at).apply()
    }

    fun markSeenOn(now: Long) {
        prefs.edit().putLong(KEY_LAST_SEEN_ON, now).apply()
    }

    /** `onServiceConnected`: bound again, any unbind time is history, and capture is ON right now. */
    fun markConnected(now: Long) {
        prefs.edit()
            .putBoolean(KEY_SERVICE_BOUND, true)
            .remove(KEY_UNBOUND_AT)
            .putLong(KEY_LAST_SEEN_ON, now)
            .apply()
    }

    /** `onUnbind`: switched off, and we know exactly when. */
    fun markUnbound(now: Long) {
        prefs.edit().putBoolean(KEY_SERVICE_BOUND, false).putLong(KEY_UNBOUND_AT, now).apply()
    }

    // ── Capture liveness (shared hooks; see CaptureWatch.noteQualifiedCapture / noteUnmatchedBankDebit) ──

    /** When the screen reader last recorded a payment; 0 = never (or not since this was added). */
    fun lastQualifiedAt(): Long = prefs.getLong(KEY_LAST_QUALIFIED, 0L)

    /** Bank-proven UPI debits that arrived with no screen capture since the last recorded payment. */
    fun unmatchedBankDebits(): Int = prefs.getInt(KEY_UNMATCHED_DEBITS, 0)

    /** When the most recent unmatched bank debit arrived; 0 = none since the last recorded payment. */
    fun lastUnmatchedAt(): Long = prefs.getLong(KEY_LAST_UNMATCHED, 0L)

    fun markQualified(now: Long) {
        prefs.edit().putLong(KEY_LAST_QUALIFIED, now).putInt(KEY_UNMATCHED_DEBITS, 0)
            .remove(KEY_LAST_UNMATCHED).apply()
    }

    fun markUnmatchedDebit(now: Long) {
        prefs.edit().putInt(KEY_UNMATCHED_DEBITS, unmatchedBankDebits() + 1)
            .putLong(KEY_LAST_UNMATCHED, now).apply()
    }

    fun liveness(): CaptureLiveness =
        CaptureLiveness(lastQualifiedAt(), unmatchedBankDebits(), lastUnmatchedAt())

    companion object {
        private const val KEY_PAUSED_KIND = "pausedSinceKind"
        private const val KEY_UNBOUND_AT = "unboundAt"
        private const val KEY_LAST_SEEN_ON = "lastSeenOnAt"
        private const val KEY_LISTED_UNBOUND_SINCE = "listedUnboundSince"
        private const val KEY_LAST_QUALIFIED = "lastQualifiedAt"
        private const val KEY_UNMATCHED_DEBITS = "unmatchedBankDebits"
        private const val KEY_LAST_UNMATCHED = "lastUnmatchedAt"
        private const val KEY_A11Y = "a11y"
        private const val KEY_SMS = "sms"
        private const val KEY_BATT = "batt"
        private const val KEY_CHECKED = "checkedAt"
        private const val KEY_PAUSED_SINCE = "pausedSince"
        private const val KEY_LAST_NOTIFIED = "lastNotifiedAt"
        private const val KEY_SERVICE_BOUND = "serviceBound"
    }
}

/**
 * Is capture actually RECORDING, not just switched on? A snapshot for the "Last payment recorded" line that
 * is still waiting on a mockup, so this is state and words only: no screen, no notification.
 *
 * The gap it watches: the service can be bound and receiving events while every screen is rejected (say an
 * update renames Google Pay's "Pay" button). [CaptureWatch.isPaused] stays false then, so only these stamps
 * can show it: when the screen reader last recorded a payment, and how many bank-proven UPI debits have
 * arrived since with no screen capture to match. Read it with [CaptureWatch.liveness].
 */
data class CaptureLiveness(
    /** When the screen reader last recorded a payment; 0 = never. */
    val lastQualifiedAt: Long,
    /** Bank-proven UPI debits with no matching screen capture since [lastQualifiedAt]. */
    val unmatchedBankDebits: Int,
    /** When the latest of those arrived; 0 = none. */
    val lastUnmatchedAt: Long,
) {
    /** "Last payment recorded 2h ago" / "No payment recorded yet". */
    fun label(now: Long): String =
        if (lastQualifiedAt <= 0L) "No payment recorded yet"
        else "Last payment recorded ${DateTime.ago(lastQualifiedAt, now)}"

    /**
     * The softer second line, or null when every bank-proven debit was also seen on screen:
     * "1 bank payment since then wasn't seen on screen" / "3 bank payments since then weren't seen on screen".
     * With no recorded payment at all there is no "then", so it reads "so far" instead.
     */
    fun unmatchedLabel(): String? {
        if (unmatchedBankDebits <= 0) return null
        val since = if (lastQualifiedAt > 0L) "since then" else "so far"
        return if (unmatchedBankDebits == 1) "1 bank payment $since wasn't seen on screen"
        else "$unmatchedBankDebits bank payments $since weren't seen on screen"
    }
}
