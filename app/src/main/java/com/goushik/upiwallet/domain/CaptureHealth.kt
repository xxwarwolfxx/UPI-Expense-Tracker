package com.goushik.upiwallet.domain

import android.content.Context
import com.goushik.upiwallet.capture.A11yCaptureService
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
 * migration needed for a transient flag). The daily worker writes here; the Status screen reads it
 * to show "last checked" and to surface a regression the user wasn't watching for.
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

    companion object {
        private const val KEY_A11Y = "a11y"
        private const val KEY_SMS = "sms"
        private const val KEY_BATT = "batt"
        private const val KEY_CHECKED = "checkedAt"
    }
}
