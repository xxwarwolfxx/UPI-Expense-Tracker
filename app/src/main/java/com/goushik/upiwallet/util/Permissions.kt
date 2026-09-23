package com.goushik.upiwallet.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.goushik.upiwallet.capture.A11yCaptureService

/**
 * Detection + routing for the validated capture permission set: Accessibility · SMS · Battery.
 *
 * Sideload reality (Android 16 / API 36): Accessibility AND SMS are restricted-for-sideload —
 * cleared once via App Info → ⋮ → "Allow restricted settings". There is NO in-app shortcut to
 * that toggle, so the most we can do is deep-link to App Info ([appDetails]). Battery is the one
 * direct-grant (system dialog). POST_NOTIFICATIONS is asked once on Home, for the "capture paused"
 * reminder (and the opt-in budget nudge) — the two things the app will ever notify about.
 */
object Permissions {

    /**
     * True iff *our* AccessibilityService is enabled (not just "some a11y service").
     *
     * Asks the system's live service registry FIRST. Reading
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` alone is the bug behind public issue #1: on
     * Android 14+ that string is not updated synchronously, so a user who has just switched the service on
     * comes back to an app that still says it is off — and onboarding strands them on the permission step.
     * `AccessibilityManager` reflects what is actually bound, immediately.
     *
     * The settings string is still consulted as a fallback, so a service that is granted but momentarily
     * unbound (right after a boot, say) does not read as "permission missing". Whether capture is genuinely
     * running is a separate question: [com.goushik.upiwallet.domain.CaptureWatch.isPaused] says "paused"
     * when capture is off, or on but not running (a live service instance in this process, the service's
     * own unbind, and a 5-minute grace for "listed in Settings but not running" — see
     * [com.goushik.upiwallet.domain.CaptureReminder.pausedState]). That is what the Home banner, the Settings
     * row, the widgets and the reminder show. Whether it is still RECORDING payments is
     * [com.goushik.upiwallet.domain.CaptureWatch.liveness] — kept, but not on any screen yet.
     */
    fun isA11yEnabled(ctx: Context): Boolean = isA11yBound(ctx) || isA11yInSettings(ctx)

    /** Bound and running right now, per the system's live registry. */
    fun isA11yBound(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val running = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrNull() ?: return false
        return running.any {
            val svc = it.resolveInfo?.serviceInfo ?: return@any false
            svc.packageName == ctx.packageName && svc.name == A11yCaptureService::class.java.name
        }
    }

    /** Switched on in Settings (the persisted list) — which is NOT the same as running. */
    fun isA11yInSettings(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) {
            if (a11yEntryMatches(s, ctx.packageName, A11yCaptureService::class.java.name)) return true
        }
        return false
    }

    /**
     * Does one entry of the enabled-services setting name the service [cls] of package [pkg]?
     *
     * The same entry comes in two spellings. The Settings app writes the long form
     * ("com.goushik.upiwallet/com.goushik.upiwallet.capture.A11yCaptureService"); Android itself rewrites the
     * whole list in the short form ("com.goushik.upiwallet/.capture.A11yCaptureService") whenever any
     * accessibility app is force-stopped, updated or removed. A leading "." is expanded against the package,
     * exactly as `ComponentName.unflattenFromString` does, so both spellings match. Comparing the raw string
     * missed the short form, and the release app then read its own switch as off while it was on.
     */
    fun a11yEntryMatches(entry: String, pkg: String, cls: String): Boolean {
        val slash = entry.indexOf('/')
        if (slash <= 0) return false
        val entryPkg = entry.substring(0, slash).trim()
        var entryCls = entry.substring(slash + 1).trim()
        if (entryCls.startsWith(".")) entryCls = entryPkg + entryCls
        return entryPkg.equals(pkg, ignoreCase = true) && entryCls.equals(cls, ignoreCase = true)
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

    // AOSP Settings reads these (SettingsActivity / SubSettings) to scroll to + highlight one entry.
    // Not public API, but purely additive: skins that don't know them open the plain list unchanged.
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

    /** Accessibility settings, asking Pixel/AOSP to scroll to and highlight OUR capture service. */
    fun accessibilitySettings(ctx: Context): Intent {
        val component = ComponentName(ctx, A11yCaptureService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) },
            )
        }
    }

    /** Launch [accessibilitySettings], falling back to the plain list on any OEM that chokes. */
    fun openAccessibilitySettings(ctx: Context) {
        runCatching { ctx.startActivity(accessibilitySettings(ctx)) }
            .onFailure {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
    }

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
