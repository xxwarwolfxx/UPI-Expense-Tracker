package com.goushik.upiwallet.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.goushik.upiwallet.R
import com.goushik.upiwallet.domain.OutageStart
import com.goushik.upiwallet.domain.SinceKind
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Permissions

/**
 * The "capture is paused" reminder — the app's second notification, after the opt-in budget nudge
 * ([com.goushik.upiwallet.domain.budget.BudgetAlerts]). Not opt-in: silently losing payments is the
 * one failure this app must never hide (the owner's call, 2026-09-15). Its own channel, so it can still be
 * muted from system settings. Fail-silent — a missing permission just means no buzz.
 *
 * The time in it tells you which payments to enter by hand, so it only claims what is known: an exact
 * "off since 2:14 PM" when the service reported its own switch-off, "stopped sometime after 2:14 PM" when
 * the outage was only noticed later (a force-stop kills the app without a word, and the first check can
 * come much later), and no time at all when capture was never seen on. See [bodyText].
 */
object CaptureAlerts {
    private const val CHANNEL_ID = "capture-health"
    private const val NOTIF_ID = 4300

    const val TITLE = "Payments aren't being recorded"

    /** The day + clock part: "2:14 PM" today, "yesterday, 2:14 PM", else "12 May, 2:14 PM". */
    fun whenLabel(at: Long, now: Long): String {
        val time = DateTime.time(at)
        return when (val day = DateTime.sectionLabel(at, now)) {
            "Today" -> time
            "Yesterday" -> "yesterday, $time"
            else -> "$day, $time"
        }
    }

    /** Human "since" phrase: "since 2:14 PM" today, "since yesterday, 2:14 PM", else "since 12 May, 2:14 PM". */
    fun sinceLabel(pausedSince: Long, now: Long): String = "since ${whenLabel(pausedSince, now)}"

    /** The lower-bound phrase for an outage noticed late: "sometime after 2:14 PM" (same day forms as above). */
    fun afterLabel(lastSeenOn: Long, now: Long): String = "sometime after ${whenLabel(lastSeenOn, now)}"

    /**
     * The notification's body, worded to match how sure we are about when capture stopped.
     *
     * @param serviceLabel the switch's name exactly as the Accessibility list shows it (the service's
     *   `a11y_service_label`: "UPI Expense Tracker capture", or the test copy's own). The stuck wording asks
     *   the user to find that switch, and on phones whose Settings ignore the highlight it must be findable
     *   by name.
     */
    fun bodyText(start: OutageStart, now: Long, serviceLabel: String, stuck: Boolean = false): String = if (stuck) {
        // The switch still shows ON, so "turn it back on" would send the owner to a switch that looks fine.
        when (start.kind) {
            SinceKind.UNKNOWN -> "Capture has stopped, even though Accessibility still shows it on. " +
                "Tap, then switch $serviceLabel off and on again."
            else -> "Capture stopped ${afterLabel(start.at, now)}, even though Accessibility still shows " +
                "it on. Tap, then switch $serviceLabel off and on again."
        }
    } else when (start.kind) {
        SinceKind.EXACT ->
            "Accessibility for UPI ET has been off ${sinceLabel(start.at, now)}. Tap to turn it back on."
        SinceKind.AFTER ->
            "Accessibility for UPI ET is off. It stopped ${afterLabel(start.at, now)}. Tap to turn it back on."
        SinceKind.UNKNOWN ->
            "Accessibility for UPI ET is off. Tap to turn it back on."
    }

    /**
     * @param renotify a 5-hour repeat. The notification is set to alert only once, so an update to one
     *   still in the shade stays silent (no double buzz if two posts ever land together); a repeat
     *   therefore removes the old one first, so the owner's every-5-hours nag still buzzes.
     * @return true iff a notification was actually posted (false = permission off → nothing shown).
     */
    fun post(
        context: Context,
        start: OutageStart,
        now: Long = System.currentTimeMillis(),
        renotify: Boolean = false,
        stuck: Boolean = false,
    ): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false    // permission off → nothing to post
        ensureChannel(context)
        val text = bodyText(start, now, context.getString(R.string.a11y_service_label), stuck)
        val open = PendingIntent.getActivity(
            context, 0, Permissions.accessibilitySettings(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_refresh) // placeholder glyph, same as the budget nudge
            // Same words as [TITLE] in a release build; the debug copy's resource reads "TEST · …".
            .setContentTitle(context.getString(R.string.capture_off_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        if (renotify) nm.cancel(NOTIF_ID)
        notifyGuarded(nm, notif)
        return true
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture health", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder when payment capture has been switched off."
            },
        )
    }

    // Guarded by areNotificationsEnabled() in post(); lint can't see across the call.
    @Suppress("MissingPermission")
    private fun notifyGuarded(nm: NotificationManagerCompat, notif: android.app.Notification) {
        nm.notify(NOTIF_ID, notif)
    }
}
