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
import com.goushik.upiwallet.domain.HealthStore
import java.util.concurrent.TimeUnit

/**
 * Daily heartbeat: re-reads the capture permission set and records a snapshot, so a silently-revoked
 * permission (OS kill, OEM auto-revert) is detected even when the app is closed. Posts NOTHING — the
 * regression surfaces only as an in-app warning on the Status screen (the product stays silent).
 */
class HealthCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val snap = CaptureHealth.snapshot(applicationContext)
        HealthStore(applicationContext).save(snap)
        Log.d(TAG, "health: a11y=${snap.a11yEnabled} sms=${snap.smsGranted} batt=${snap.batteryExempt}")
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
