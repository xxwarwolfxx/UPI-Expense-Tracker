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
import com.goushik.upiwallet.domain.CaptureHealth
import com.goushik.upiwallet.domain.CaptureWatch
import com.goushik.upiwallet.domain.HealthStore
import java.util.concurrent.TimeUnit

/**
 * Daily check (plus one run soon after launch): re-reads the capture permission set and records a
 * snapshot, so a silently-revoked permission (an OEM auto-revert, say) is noticed without the app being
 * on screen. Since 2026-09-15 it also runs the [CaptureWatch] tick, so an outage found here posts the
 * "capture paused" reminder and flips the widgets (the 15-minute ReconcileWorker sweep does the same).
 *
 * The limit, verified on the device: a Force stop (from App info, or a phone "cleaner" that force-stops)
 * cancels this job along with everything else the app scheduled, and Android runs none of it until the
 * app is opened again. So that outage is caught on the next app open, not while the app stays closed.
 */
class HealthCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val snap = CaptureHealth.snapshot(applicationContext)
        HealthStore(applicationContext).save(snap)
        Log.d(TAG, "health: a11y=${snap.a11yEnabled} sms=${snap.smsGranted} batt=${snap.batteryExempt}")
        CaptureWatch.tick(applicationContext)
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG, "health check failed", t)
        Result.retry()
    }

    companion object {
        private const val TAG = "UpiWallet"
        private const val PERIODIC = "health-check"
        private const val ONESHOT = "health-check-now"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            // Run once soon after launch to populate the readout, then daily.
            wm.enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<HealthCheckWorker>().build(),
            )
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthCheckWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
