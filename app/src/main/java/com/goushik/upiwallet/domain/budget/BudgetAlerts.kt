package com.goushik.upiwallet.domain.budget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.goushik.upiwallet.R
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.insights.InsightsPeriod

/**
 * The 80% / over-budget nudge — opt-in (Settings → Budgets → "Nudge me"). One of the app's two
 * notifications; the other is the capture-off reminder ([com.goushik.upiwallet.capture.CaptureAlerts]),
 * which is not opt-in. Runs on the WRITE PATH only: a fresh payment is the only thing that can push you
 * past a line, so there's no periodic polling. Fires at most once per threshold (80, then 100) per period
 * via the budget row's `lastAlerted*` markers, which re-arm when the day/week/month window rolls.
 * Fail-silent — a missing permission or channel just means no buzz, never a crash on the capture path.
 * The text is [budgetNudgeCopy]: percent only, never a rupee amount.
 */
object BudgetAlerts {
    private const val CHANNEL_ID = "budget-alerts"
    private const val NOTIF_BASE = 4200

    suspend fun check(context: Context) {
        if (!ServiceLocator.uiPrefs.budgetAlerts.value) return
        val repo = ServiceLocator.repository
        val budgets = repo.budgets().filter { it.limitPaise > 0L }
        if (budgets.isEmpty()) return

        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return          // permission off → nothing to post
        ensureChannel(context)

        val profile = repo.profile()
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()
        val txns = repo.transactions()
        val now = System.currentTimeMillis()

        for (b in budgets) {
            val status = budgetStatus(b, txns, ownVpas, ownNames, now)
            val reached = status.reachedThreshold()
            // The marker only counts within its own window — a new period (different start) re-arms to 0.
            val already = if (b.lastAlertedPeriodStart == status.windowStart) b.lastAlertedThreshold else 0
            when {
                reached > already -> {
                    notify(context, nm, status)
                    repo.markBudgetAlerted(b.period, reached, status.windowStart)
                }
                // Carry the marker into the new window even when nothing's reached, so the next crossing fires.
                b.lastAlertedPeriodStart != status.windowStart && b.lastAlertedThreshold != 0 ->
                    repo.markBudgetAlerted(b.period, 0, status.windowStart)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A heads-up when you near or pass a spending limit."
            },
        )
    }

    // Guarded by areNotificationsEnabled() above; lint can't see across the suspend call.
    @Suppress("MissingPermission")
    private fun notify(context: Context, nm: NotificationManagerCompat, status: BudgetStatus) {
        // NOTE: placeholder icon — to be red-penned later.
        val (title, text) = budgetNudgeCopy(status)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_refresh) // placeholder glyph
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        // One stable id per period so daily/weekly/monthly nudges don't clobber each other.
        nm.notify(NOTIF_BASE + status.period.ordinal, notif)
    }

}

/**
 * PURE — the nudge's (title, text) for a budget that has reached a threshold. Three cases, matching what
 * Home and Budgets say for the same state:
 *  - exactly AT the cap → "reached" (Home says "At your limit"; never "gone over … by ₹0.00"),
 *  - past it → over, as a percent,
 *  - 80%+ → nearing, as a percent.
 * No rupee amount anywhere: a notification shows on the lock screen, where anyone can read it, and how
 * much of a budget is left is nobody else's business. Host unit-tested (BudgetStatusTest).
 */
fun budgetNudgeCopy(status: BudgetStatus): Pair<String, String> {
    val word = periodWord(status.period)
    return when {
        status.isOver && status.remainingPaise == 0L ->
            "At your $word limit" to "You've reached your $word budget."
        // A sliver over (₹1,000.50 of ₹1,000) truncates to 100% — "used 100%" would read as "at", so say "over".
        status.isOver && status.pct <= 100 ->
            "Over your $word budget" to "You've gone over your $word budget."
        status.isOver ->
            "Over your $word budget" to "You've used ${status.pct}% of your $word budget."
        else ->
            "Nearing your $word budget" to "You've used ${status.pct}% of your $word budget."
    }
}

private fun periodWord(period: InsightsPeriod): String = when (period) {
    InsightsPeriod.DAY -> "daily"
    InsightsPeriod.WEEK -> "weekly"
    InsightsPeriod.MONTH -> "monthly"
    else -> "spending"
}
