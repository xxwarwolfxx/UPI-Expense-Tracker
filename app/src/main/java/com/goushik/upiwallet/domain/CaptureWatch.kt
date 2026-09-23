package com.goushik.upiwallet.domain

import android.content.Context
import android.util.Log
import com.goushik.upiwallet.capture.CaptureAlerts
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.util.Dbg
import com.goushik.upiwallet.util.Permissions
import com.goushik.upiwallet.widget.WidgetUpdater
import com.goushik.upiwallet.work.ReconcileWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the reminder rule wants done this tick. */
enum class ReminderAction { NOTIFY, CLEAR, NONE }

/**
 * How sure we are about when an outage began — it decides the reminder's wording.
 *  - [EXACT]: the service told us it was switched off (onUnbind), so "off since 2:14 PM" is a fact.
 *  - [AFTER]: we only noticed later (a sweep, an app open). The last time capture was seen ON is a lower
 *    bound, so the honest wording is "stopped sometime after 2:14 PM".
 *  - [UNKNOWN]: capture was never seen ON since this was added (a restore that skipped setup, or the first
 *    run after an update). No time is shown rather than a made-up one.
 */
enum class SinceKind { EXACT, AFTER, UNKNOWN }

/** When an outage began ([at], epoch ms) and how sure we are about it ([kind]). */
data class OutageStart(val at: Long, val kind: SinceKind)

/**
 * The pure "when do we nag" rule — no Android in here so it is host-testable with literal clocks
 * (the [com.goushik.upiwallet.capture.RecentCaptures] idiom).
 *
 * Instant on the first detection, then once per [INTERVAL_MS] while capture stays paused; a single
 * CLEAR the moment it comes back, which also re-arms the instant alert for the next outage.
 */
object CaptureReminder {
    /** The owner's call (2026-09-15): every 5 hours while off. */
    const val INTERVAL_MS = 5 * 60 * 60 * 1000L

    /**
     * How long "switched on in Settings, but not running" is tolerated before it counts as paused. Long
     * enough to ride out a reboot or an update re-binding the service; short enough to catch the case seen
     * on the owner's Pixel on 2026-09-23: after the process was killed, Android left the service listed as
     * on but never brought it back, so nothing was captured while every surface said "Healthy".
     */
    const val LISTED_UNBOUND_GRACE_MS = 5 * 60 * 1000L

    /**
     * One read of the paused state. [running] = actually bound now; [listedUnboundSince] = the grace clock
     * to persist; [stuck] = paused while Settings still shows the switch ON (the service died and Android
     * didn't restart it) — the fix is "switch it off and on again", not "turn it on", so the words differ.
     */
    data class PauseRead(
        val paused: Boolean,
        val running: Boolean,
        val listedUnboundSince: Long,
        val stuck: Boolean = false,
    )

    /**
     * The pure core of [CaptureWatch.isPaused].
     *  - the service reported its own unbind ([serviceBoundFlag] false) → paused, instantly;
     *  - bound now → running;
     *  - not bound and not listed → switched off → paused;
     *  - listed but not bound → paused only once that has lasted [LISTED_UNBOUND_GRACE_MS].
     */
    fun pausedState(
        bound: Boolean,
        listed: Boolean,
        serviceBoundFlag: Boolean,
        listedUnboundSince: Long,
        now: Long,
        graceMs: Long = LISTED_UNBOUND_GRACE_MS,
    ): PauseRead = when {
        !serviceBoundFlag -> PauseRead(paused = true, running = bound, listedUnboundSince = 0L)
        bound -> PauseRead(paused = false, running = true, listedUnboundSince = 0L)
        !listed -> PauseRead(paused = true, running = false, listedUnboundSince = 0L)
        else -> {
            val since = if (listedUnboundSince in 1..now) listedUnboundSince else now
            val paused = now - since >= graceMs
            PauseRead(paused = paused, running = false, listedUnboundSince = since, stuck = paused)
        }
    }

    /** Slack after the grace runs out, so the follow-up read is safely past it. */
    const val GRACE_FOLLOW_UP_MARGIN_MS = 5_000L

    /**
     * How long until a grace clock that [read] has just STARTED runs out, or null when there is nothing to
     * schedule: the clock is not new (it was [previousSince] already, or there is none), or it is already
     * paused. Without a follow-up at that moment, nothing re-reads the state when the 5 minutes end (the
     * widgets, the Home banner and the reminder only move on a tick), so "paused" waited for the next
     * unrelated tick — up to 15 minutes, or longer for a rarely used app.
     */
    fun graceFollowUpDelayMs(
        previousSince: Long,
        read: PauseRead,
        now: Long,
        graceMs: Long = LISTED_UNBOUND_GRACE_MS,
    ): Long? {
        val since = read.listedUnboundSince
        if (read.paused || read.running || since == 0L || since == previousSince) return null
        return (since + graceMs - now).coerceAtLeast(0L) + GRACE_FOLLOW_UP_MARGIN_MS
    }

    /**
     * [onboarded] = setup is finished. Before that, capture being off is not an outage — it was never on —
     * so nothing is ever posted, and any stamps left behind are cleared so an install-time "since" can't
     * leak into the first real reminder. (The widgets already show their set-up card first, for the same
     * reason.) Android 12 is where this bites: no notification permission there, so a fresh install
     * would otherwise be told to "turn it back on" on the Welcome screen.
     */
    fun decide(
        paused: Boolean,
        pausedSince: Long,
        lastNotifiedAt: Long,
        now: Long,
        intervalMs: Long = INTERVAL_MS,
        onboarded: Boolean = true,
    ): ReminderAction = when {
        !onboarded -> if (pausedSince != 0L || lastNotifiedAt != 0L) ReminderAction.CLEAR else ReminderAction.NONE
        paused && pausedSince == 0L -> ReminderAction.NOTIFY          // first sighting → instant
        paused && now - lastNotifiedAt >= intervalMs -> ReminderAction.NOTIFY
        paused -> ReminderAction.NONE                                  // inside the window → hush
        pausedSince != 0L -> ReminderAction.CLEAR                      // back on → clear + re-arm
        else -> ReminderAction.NONE
    }

    /**
     * Where a NEW outage starts, from what is on record at the moment it is first noticed.
     *
     * The service's own unbind is exact, whichever tick gets to it first (the flag and its time are written
     * synchronously in onUnbind, so even a Home resume that races ahead of the unbind's own tick sees them).
     * Every other path — a force-stop, an OEM cleaner, an OS revoke — kills the process without an unbind,
     * and the first tick can come hours later. Stamping `now` there understated the gap, and the gap is
     * what tells the owner which payments to enter by hand; the last time capture was seen ON is the honest
     * lower bound instead.
     */
    fun outageStart(serviceBound: Boolean, unboundAt: Long, lastSeenOnAt: Long, now: Long): OutageStart = when {
        !serviceBound && unboundAt > 0L -> OutageStart(unboundAt, SinceKind.EXACT)
        lastSeenOnAt in 1..now -> OutageStart(lastSeenOnAt, SinceKind.AFTER)
        // Nothing to anchor on. `now` only marks the outage as noticed (pausedSince must be non-zero);
        // UNKNOWN keeps it off the notification.
        else -> OutageStart(now, SinceKind.UNKNOWN)
    }
}

/**
 * Detects "capture is paused" and reacts: posts / clears the reminder and repaints every widget.
 *
 * Paused means off, or on but not running. Each read ([CaptureReminder.pausedState]) takes three inputs:
 * whether a service instance is live in this process (`A11yCaptureService.live`), whether Settings lists the
 * switch as on ([Permissions.isA11yInSettings]), and the service's own word ([HealthStore.serviceBound],
 * written `false` only from `A11yCaptureService.onUnbind`). Then:
 *  - the service reported its own unbind → paused, at once (instant, and immune to the settings string
 *    lagging behind the toggle);
 *  - a live instance → running;
 *  - not listed in Settings → switched off (process death, `install -r`, an OEM auto-revert) → paused;
 *  - listed but not running → a 5-minute grace ([CaptureReminder.LISTED_UNBOUND_GRACE_MS]) to ride out a
 *    boot or an update re-binding it, then paused and "stuck": the switch shows ON, so the words ask for
 *    off-and-on. When a read starts that clock it also books one follow-up run for when it runs out
 *    ([CaptureReminder.graceFollowUpDelayMs]), so every surface flips on time even with the app closed.
 *
 * What it cannot do: every tick runs inside the app's own process. After a Force stop (from App info, or a
 * phone "cleaner" that force-stops) Android runs none of the app's background work until the app is opened
 * again, so that outage is noticed — and the reminder posted — on the next app open, not while it stays
 * closed. The "since" wording is honest about that (see [CaptureReminder.outageStart]).
 *
 * [tick] is the one entry point; it is called from the service's connect/unbind, the 15-minute
 * [com.goushik.upiwallet.work.ReconcileWorker] sweep (and its one-off run when a grace clock ends), the daily
 * health check and every Home/Settings resume. The cadence lives in [CaptureReminder], not in WorkManager's
 * period.
 */
object CaptureWatch {
    private const val TAG = "UpiWallet"

    /**
     * One tick at a time, process-wide. Without it, the resume tick, the worker tick and the service's own
     * tick could all read "no outage yet" together and each post the reminder, or a stale read could
     * re-post it just after the connect tick had cleared it.
     */
    private val tickLock = Mutex()

    private val _changes = MutableStateFlow(0L)

    /**
     * Bumps whenever the paused state may have changed (a lifecycle callback from the service, or any tick).
     * Screens re-read [isPaused] on it, so the Home banner and the Settings row follow the widgets live while
     * the app is open — not only on the next resume. It is a signal to RE-READ, never to tick (a tick bumps
     * it, so ticking on it would loop).
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun bump() = _changes.update { it + 1 }

    /** THE definition of "capture is paused" — widgets, the reminder, Home and Settings all read this. */
    fun isPaused(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = read(ctx, now).paused

    /** Paused although Settings shows the switch on — see [CaptureReminder.PauseRead.stuck]. */
    fun isStuck(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = read(ctx, now).stuck

    private fun read(ctx: Context, now: Long): CaptureReminder.PauseRead {
        val store = HealthStore(ctx)
        val previousSince = store.listedUnboundSince()
        val r = CaptureReminder.pausedState(
            // Running = an instance is connected in this process (see A11yCaptureService.live). The system's
            // bound list can keep a dead connection, so it is not trusted on its own.
            bound = com.goushik.upiwallet.capture.A11yCaptureService.live,
            listed = Permissions.isA11yInSettings(ctx),
            serviceBoundFlag = store.serviceBound(),
            listedUnboundSince = previousSince,
            now = now,
        )
        if (r.listedUnboundSince != previousSince) {
            store.setListedUnboundSince(r.listedUnboundSince)
            // A new grace clock: book the re-check for when it runs out (nothing else would tick then).
            CaptureReminder.graceFollowUpDelayMs(previousSince, r, now)?.let { delay ->
                runCatching { ReconcileWorker.scheduleCaptureRecheck(ctx, delay) }
                    .onFailure { Log.w(TAG, "capture re-check not scheduled: ${it.javaClass.simpleName}") }
            }
        }
        return r
    }

    /** Read → decide → act → repaint. Safe to call from any thread/scope; never throws. */
    suspend fun tick(ctx: Context, now: Long = System.currentTimeMillis()) {
        try {
            val onboarded = isOnboarded()
            tickLock.withLock { decideAndAct(ctx, now, onboarded) }
        } catch (t: Throwable) {
            Log.w(TAG, "capture watch tick failed: ${t.javaClass.simpleName}")
        }
        bump()
        // Widgets read the paused state at push time, so a repaint is the whole "show it" step.
        runCatching { WidgetUpdater.loadAndPush(ctx) }
    }

    private fun decideAndAct(ctx: Context, now: Long, onboarded: Boolean) {
        val store = HealthStore(ctx)
        val read = read(ctx, now)
        val paused = read.paused
        // The lower bound for an outage we only notice later — stamped only while the service is really
        // running, never during the grace period of "listed but not bound".
        if (read.running) store.markSeenOn(now)
        val action = CaptureReminder.decide(
            paused, store.pausedSince(), store.lastNotifiedAt(), now, onboarded = onboarded,
        )
        when (action) {
            ReminderAction.NOTIFY -> {
                // A 5-hour repeat keeps the outage's original start; only a new outage works it out.
                val start = store.outageStart() ?: CaptureReminder.outageStart(
                    store.serviceBound(), store.unboundAt(), store.lastSeenOnAt(), now,
                )
                val repeat = store.lastNotifiedAt() != 0L
                val posted = CaptureAlerts.post(ctx, start, now, renotify = repeat, stuck = read.stuck)
                // An unposted reminder (notification permission not granted yet) must not eat the
                // 5-hour window: leave lastNotifiedAt at 0 so the very next tick tries again.
                store.markPaused(start, notifiedAt = if (posted) now else 0L)
                Dbg.d { "capture paused (${start.kind}) → reminder ${if (posted) "posted" else "NOT posted (no permission)"}" }
            }
            ReminderAction.CLEAR -> {
                store.clearPaused()
                CaptureAlerts.cancel(ctx)
                Dbg.d { if (onboarded) "capture back on → reminder cleared" else "setup not finished → reminder state cleared" }
            }
            ReminderAction.NONE -> Unit
        }
    }

    /** Setup finished? A read failure counts as yes: a hiccup must never silence a real outage. */
    private suspend fun isOnboarded(): Boolean =
        runCatching { ServiceLocator.repository.profile()?.onboardedAt != null }.getOrDefault(true)

    /**
     * The service just bound. Called SYNCHRONOUSLY from `A11yCaptureService.onServiceConnected`, before its
     * tick is launched, so the flag always matches the last lifecycle callback however the ticks interleave.
     * Also the freshest "seen ON" stamp there is — written even before setup is finished, so a user who is
     * force-stopped right after turning capture on still gets an honest lower bound.
     */
    fun serviceConnected(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching { HealthStore(ctx).markConnected(now) }
        bump()
    }

    /** The service is being switched off — the only writer of `serviceBound=false`. Synchronous, like [serviceConnected]. */
    fun serviceUnbound(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching { HealthStore(ctx).markUnbound(now) }
        bump()
    }

    /**
     * Shared hook: the screen reader just recorded a payment (called by A11yCaptureService on a QUALIFIED
     * capture). Feeds "Last payment recorded" and resets the not-recording counter. Cheap and synchronous.
     */
    fun noteQualifiedCapture(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching { HealthStore(ctx).markQualified(now) }
    }

    /**
     * Shared hook: a bank SMS proved a UPI debit that no screen capture matched, while capture was ON
     * (called from the SMS path on a standalone debit). Counted, not alerted on — the "capture is on but
     * not recording" signal is watched on the owner's phone before it ever drives a notification.
     */
    fun noteUnmatchedBankDebit(ctx: Context, now: Long = System.currentTimeMillis()) {
        runCatching { if (!isPaused(ctx)) HealthStore(ctx).markUnmatchedDebit(now) }
    }

    /**
     * What the (not yet designed) "Last payment recorded" line will show. Read-only, no alerting — see
     * [CaptureLiveness.label] for the words.
     */
    fun liveness(ctx: Context): CaptureLiveness = HealthStore(ctx).liveness()
}
