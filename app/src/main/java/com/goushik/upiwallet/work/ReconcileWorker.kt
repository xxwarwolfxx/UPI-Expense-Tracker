package com.goushik.upiwallet.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.goushik.upiwallet.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Cross-process / a11y-off safety net. Ages PENDING rows past the match window to UNCONFIRMED so a
 * confirm-sheet capture whose episode never reached a terminal (process killed mid-PIN, empty success
 * screen) still becomes a reviewable row rather than being stranded forever.
 */
class ReconcileWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        ServiceLocator.reconciler.sweepStalePending()
        // Battery-free widget freshness: this worker already runs every 15 min, so repaint every placed
        // widget here too — that rolls the day/week/month windows (e.g. "today" resets at midnight) without
        // adding any new wakeup.
        com.goushik.upiwallet.widget.WidgetUpdater.loadAndPush(applicationContext)
        // Catch budget crossings from manual adds / aged-in rows (and re-arm rolled periods) — opt-in.
        com.goushik.upiwallet.domain.budget.BudgetAlerts.check(applicationContext)
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG, "reconcile sweep failed", t)
        Result.retry()
    }

    companion object {
        private const val TAG = "UpiWallet"
        private const val NAME = "reconcile-sweep"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReconcileWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
