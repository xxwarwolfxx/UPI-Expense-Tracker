package com.goushik.upiwallet.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.goushik.upiwallet.capture.A11yCaptureService

/**
 * Detection + routing for the validated capture permission set: Accessibility · SMS · Battery.
 *
 * Sideload reality (Android 16 / API 36): Accessibility AND SMS are restricted-for-sideload —
 * cleared once via App Info → ⋮ → "Allow restricted settings". There is NO in-app shortcut to
 * that toggle, so the most we can do is deep-link to App Info ([appDetails]). Battery is the one
 * direct-grant (system dialog). POST_NOTIFICATIONS is intentionally not used (silent app).
 */
object Permissions {

    /** True iff *our* AccessibilityService is enabled (not just "some a11y service"). */
    fun isA11yEnabled(ctx: Context): Boolean {
        val expected = "${ctx.packageName}/${A11yCaptureService::class.java.name}"
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) if (s.equals(expected, ignoreCase = true)) return true
        return false
    }

    fun isSmsGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Precise location for the opt-in Insights map. FINE specifically — COARSE (~2 km) is too coarse for
     *  neighborhood pins (Slice-B spike). A normal runtime grant (not restricted-for-sideload like a11y/SMS). */
    fun isLocationGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun isBatteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun accessibilitySettings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** App Info — the closest an app can deep-link toward the "Allow restricted settings" gate. */
    fun appDetails(ctx: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", ctx.packageName, null),
        )

    fun batteryExemption(ctx: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        )
}
