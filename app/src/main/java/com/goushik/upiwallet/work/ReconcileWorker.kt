package com.goushik.upiwallet.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goushik.upiwallet.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Cross-process / a11y-off safety net. Ages PENDING rows past the match window to UNCONFIRMED so a
 * confirm-sheet capture whose episode never reached a terminal (process killed mid-PIN, empty success
 * screen) still becomes a reviewable row rather than being stranded forever.
 *
 * It runs every 15 minutes, and once more when the capture check asks for it ([scheduleCaptureRecheck]).
 */
class ReconcileWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val swept = runReconcileSteps(
            // Battery-free widget freshness: this worker already runs every 15 min, so the tick repaints every
            // placed widget too — that rolls the day/week/month windows (e.g. "today" resets at midnight)
            // without adding any new wakeup. The tick also re-checks that capture is still ON and posts /
            // clears the "capture paused" reminder on its own 5-hour cadence (CaptureReminder) — same wakeup,
            // no new one. "5 hours" is as good as this job's scheduling: Android may run it less often for an
            // app that is rarely used and not battery-exempt, and never after a Force stop until the app is
            // opened again.
            tick = { com.goushik.upiwallet.domain.CaptureWatch.tick(applicationContext) },
            sweep = { ServiceLocator.reconciler.sweepStalePending() },
            // Catch budget crossings from manual adds / aged-in rows (and re-arm rolled periods) — opt-in.
            budget = { com.goushik.upiwallet.domain.budget.BudgetAlerts.check(applicationContext) },
            onFailure = { what, t -> Log.w(TAG, "$what failed", t) },
        )
        return if (swept) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "UpiWallet"
        private const val NAME = "reconcile-sweep"
        private const val CAPTURE_RECHECK = "capture-recheck"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * One extra run [delayMs] from now: the moment "switched on but not running" has lasted long enough
         * to count as paused (see CaptureReminder.graceFollowUpDelayMs), so the widgets, the Home banner and
         * the reminder flip then, even with the app closed, instead of at the next 15-minute run. REPLACE,
         * not KEEP: it is asked for only when a new grace clock starts, and a request left over from an
         * earlier clock would fire too early, find the new clock still running, and ask for nothing more.
         */
        fun scheduleCaptureRecheck(context: Context, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CAPTURE_RECHECK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * The order of one run. The capture check goes first and on its own: it needs nothing from the database
 * write below, so a failing sweep (a full disk, say) must never hold back the capture-off reminder. The
 * budget check runs whatever happened before it. Returns false only when the stale-payment sweep failed,
 * which is the one step worth a retry.
 */
internal suspend fun runReconcileSteps(
    tick: suspend () -> Unit,
    sweep: suspend () -> Unit,
    budget: suspend () -> Unit,
    onFailure: (what: String, t: Throwable) -> Unit,
): Boolean {
    runCatching { tick() }.onFailure { onFailure("capture check", it) }
    val swept = runCatching { sweep() }.onFailure { onFailure("reconcile sweep", it) }.isSuccess
    runCatching { budget() }.onFailure { onFailure("budget check", it) }
    return swept
}
