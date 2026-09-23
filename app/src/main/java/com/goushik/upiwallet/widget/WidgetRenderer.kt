package com.goushik.upiwallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.goushik.upiwallet.MainActivity
import com.goushik.upiwallet.R
import com.goushik.upiwallet.domain.budget.BudgetBand
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.domain.budget.budgetMeter
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.Permissions

/**
 * Builds the [RemoteViews] for each widget size from a [WidgetSnapshot]. Pure view-binding — the
 * data load happens upstream (provider onUpdate / the freshness collector). The balance + per-account
 * chips render masked unless [revealed]; the balance eye toggles it. Spend numbers (today/week/month) are
 * shown by default and masked when [spendHidden]; the spend eye toggles that. Just the amount per window —
 * no payment counts. Small carries the spend-hide eye too (so masking is consistent across all sizes); it
 * still has no reload button, keeping its 2×1 cell uncrowded.
 *
 * [capturePaused] (capture is paused: off, or on but not running) overrides every size with one "Capture
 * paused" card that taps through to Accessibility settings — a figure that has stopped counting must not
 * look live.
 */
object WidgetRenderer {

    /** True-digit dot mask — one dot per real rupee digit, no comma ("₹•••••" for ₹48,250). */
    private fun mask(paise: Long): String {
        val digits = Money.formatParts(paise).first.count(Char::isDigit)
        return "₹" + "•".repeat(digits.coerceAtLeast(1))
    }

    /** Spend amount, masked when the spend-eye has hidden it. */
    private fun spend(paise: Long, spendHidden: Boolean): String =
        if (spendHidden) mask(paise) else Money.formatParts(paise).first

    fun build(
        context: Context,
        size: WidgetSize,
        appWidgetId: Int,
        snap: WidgetSnapshot,
        revealed: Boolean,
        spendHidden: Boolean,
        capturePaused: Boolean = false,
        captureStuck: Boolean = false,
    ): RemoteViews {
        if (!snap.onboarded) return setupViews(context, appWidgetId)
        if (capturePaused) return pausedViews(context, appWidgetId, captureStuck)
        return when (size) {
            WidgetSize.SMALL -> small(context, appWidgetId, snap, spendHidden)
            WidgetSize.BIG -> big(context, appWidgetId, snap, spendHidden)
            WidgetSize.LARGE -> large(context, appWidgetId, snap, revealed, spendHidden)
            WidgetSize.BUDGET -> budget(context, appWidgetId, snap)
            WidgetSize.QUOTA -> quota(context, appWidgetId, snap)
        }
    }

    private fun small(context: Context, id: Int, snap: WidgetSnapshot, spendHidden: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_small).apply {
            setTextViewText(R.id.amount, spend(snap.monthPaise, spendHidden))
            setImageViewResource(R.id.spend_eye, if (spendHidden) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye_open)
            setOnClickPendingIntent(R.id.spend_eye, toggleSpend(context, id, WidgetSize.SMALL))
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
        }

    private fun big(context: Context, id: Int, snap: WidgetSnapshot, spendHidden: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_big).apply {
            bindSpendTriple(this, snap, spendHidden)
            bindHeaderControls(this, context, id, WidgetSize.BIG, spendHidden)
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
        }

    private fun large(context: Context, id: Int, snap: WidgetSnapshot, revealed: Boolean, spendHidden: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_large).apply {
            bindSpendTriple(this, snap, spendHidden)
            bindHeaderControls(this, context, id, WidgetSize.LARGE, spendHidden)
            if (snap.showBalance && !snap.hasBalance) {
                // Balance mode but no account set up (e.g. a spend-only user who flipped "Show account
                // balance" on): there is no starting balance, so the only honest value is a dash — the
                // same "—" Settings shows. No reveal eye (nothing to reveal, and a mask would leak the
                // digit count of a meaningless negative), no chips.
                setViewVisibility(R.id.balance_hair, View.VISIBLE)
                setViewVisibility(R.id.balance_row, View.VISIBLE)
                setViewVisibility(R.id.balance_chips, View.GONE)
                setTextViewText(R.id.balance_value, "—")
                setViewVisibility(R.id.eye_pill, View.GONE)
            } else if (snap.showBalance) {
                setViewVisibility(R.id.balance_hair, View.VISIBLE)
                setViewVisibility(R.id.balance_row, View.VISIBLE)
                setViewVisibility(R.id.balance_chips, View.VISIBLE)
                setViewVisibility(R.id.eye_pill, View.VISIBLE)
                // Balance + eye (the only sensitive numbers — masked by default).
                setTextViewText(R.id.balance_value, if (revealed) Money.formatParts(snap.availablePaise).first else mask(snap.availablePaise))
                setImageViewResource(R.id.eye_icon, if (revealed) R.drawable.ic_widget_eye_open else R.drawable.ic_widget_eye_off)
                setTextViewText(R.id.eye_label, if (revealed) "Hide" else "Reveal")
                setOnClickPendingIntent(R.id.eye_pill, toggleReveal(context, id))
                // Per-account chips (up to two) — "at setup" baselines, masked together with the balance.
                bindChip(this, snap.accounts.getOrNull(0), R.id.chip1, R.id.chip1_label, R.id.chip1_value, revealed)
                bindChip(this, snap.accounts.getOrNull(1), R.id.chip2, R.id.chip2_label, R.id.chip2_value, revealed)
            } else {
                // Spend-only mode: PURE SPEND — no balance, no reveal-eye, no chips.
                setViewVisibility(R.id.balance_hair, View.GONE)
                setViewVisibility(R.id.balance_row, View.GONE)
                setViewVisibility(R.id.balance_chips, View.GONE)
            }
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
        }

    /** The three spend windows (today/week/month) — shared by Big + Large. Amounts mask under the spend
     *  eye. Just the amount per window — no payment counts. */
    private fun bindSpendTriple(rv: RemoteViews, snap: WidgetSnapshot, spendHidden: Boolean) {
        rv.setTextViewText(R.id.day_amount, spend(snap.dayPaise, spendHidden))
        rv.setTextViewText(R.id.week_amount, spend(snap.weekPaise, spendHidden))
        rv.setTextViewText(R.id.month_amount, spend(snap.monthPaise, spendHidden))
    }

    /** The header glyphs shared by Big + Large: the spend-hide eye + the manual reload button. */
    private fun bindHeaderControls(rv: RemoteViews, context: Context, id: Int, size: WidgetSize, spendHidden: Boolean) {
        rv.setImageViewResource(R.id.spend_eye, if (spendHidden) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye_open)
        rv.setOnClickPendingIntent(R.id.spend_eye, toggleSpend(context, id, size))
        rv.setOnClickPendingIntent(R.id.refresh_btn, refresh(context, id, size))
    }

    private fun bindChip(
        rv: RemoteViews,
        account: WidgetAccount?,
        chipId: Int,
        labelId: Int,
        valueId: Int,
        revealed: Boolean,
    ) {
        if (account == null) {
            rv.setViewVisibility(chipId, View.GONE)
            return
        }
        rv.setViewVisibility(chipId, View.VISIBLE)
        rv.setTextViewText(labelId, "${account.label} · at setup")
        rv.setTextViewText(valueId, if (revealed) Money.formatParts(account.baselinePaise).first else mask(account.baselinePaise))
    }

    // The money-stack budget meter: the bottom `litTiers` note-slots are lit (money left), the rest ghosts.
    private const val BUDGET_TIERS = 6
    private val budgetTierIds = intArrayOf(R.id.tier0, R.id.tier1, R.id.tier2, R.id.tier3, R.id.tier4, R.id.tier5)

    private fun budget(context: Context, id: Int, snap: WidgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_budget).apply {
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
            if (snap.monthBudgetLimitPaise <= 0L) {
                // No monthly cap set → a quiet prompt instead of a meter.
                setViewVisibility(R.id.budget_meter, View.GONE)
                setViewVisibility(R.id.budget_empty, View.VISIBLE)
                return@apply
            }
            setViewVisibility(R.id.budget_meter, View.VISIBLE)
            setViewVisibility(R.id.budget_empty, View.GONE)
            val m = budgetMeter(snap.monthPaise, snap.monthBudgetLimitPaise, BUDGET_TIERS)
            val litRes = when (m.band) {
                BudgetBand.CALM -> R.drawable.widget_note_lit
                BudgetBand.NEAR -> R.drawable.widget_note_lit_amber
                BudgetBand.OVER -> R.drawable.widget_note_lit_coral
            }
            val pctColor = when (m.band) {
                BudgetBand.CALM -> 0xFFFFFFFF.toInt()
                BudgetBand.NEAR -> 0xFFFFD79A.toInt()
                BudgetBand.OVER -> 0xFFF2A0A4.toInt()
            }
            val headline = budgetHeadline(snap.monthPaise, snap.monthBudgetLimitPaise, m.pctLeft)
            setTextViewText(R.id.budget_pct_num, headline.pct.toString())
            setTextColor(R.id.budget_pct_num, pctColor)
            setTextColor(R.id.budget_pct_sign, pctColor)
            setTextViewText(
                R.id.budget_label,
                context.getString(
                    when (headline.label) {
                        BudgetLabel.LEFT -> R.string.widget_budget_left
                        BudgetLabel.AT_LIMIT -> R.string.widget_budget_at_limit
                        BudgetLabel.OVER -> R.string.widget_budget_over_used
                    },
                ),
            )
            for (i in budgetTierIds.indices) {
                val isLit = i >= BUDGET_TIERS - m.litTiers   // fill from the bottom of the stack
                setImageViewResource(budgetTierIds[i], if (isLit) litRes else R.drawable.widget_note_ghost)
            }
        }


    // ── QUOTA: how much of the WEEK and MONTH caps is SPENT ─────────────────────────────────────
    // No rupees, no reset countdowns, no "today" row — scoped to exactly this. The BAR carries the
    // gradient (a widget TextView cannot) and the NUMBER carries a solid band colour: the same split
    // [budget] already uses. Note this meter runs OPPOSITE to the money-stack: that one shows what is
    // left, this one shows what has gone.
    private const val QUOTA_WIDE_DP = 180

    private class QuotaRow(
        val row: Int, val label: Int, val pct: Int, val sign: Int,
        val barCalm: Int, val barNear: Int, val barOver: Int,
    )

    private val quotaWeek = QuotaRow(
        R.id.week_row, R.id.week_label, R.id.week_pct, R.id.week_pct_sign,
        R.id.week_bar_calm, R.id.week_bar_near, R.id.week_bar_over,
    )
    private val quotaMonth = QuotaRow(
        R.id.month_row, R.id.month_label, R.id.month_pct, R.id.month_pct_sign,
        R.id.month_bar_calm, R.id.month_bar_near, R.id.month_bar_over,
    )

    private fun quota(context: Context, id: Int, snap: WidgetSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quota).apply {
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
            setOnClickPendingIntent(R.id.refresh_btn, refresh(context, id, WidgetSize.QUOTA))

            val hasWeek = snap.weekBudgetLimitPaise > 0L
            val hasMonth = snap.monthBudgetLimitPaise > 0L
            setViewVisibility(R.id.week_row, if (hasWeek) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.month_row, if (hasMonth) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.quota_empty, if (hasWeek || hasMonth) View.GONE else View.VISIBLE)

            val wide = quotaIsWide(context, id)
            if (hasWeek) {
                quotaRow(this, quotaWeek, if (wide) "This week" else "Week", snap.weekPaise, snap.weekBudgetLimitPaise)
            }
            if (hasMonth) {
                quotaRow(this, quotaMonth, if (wide) "This month" else "Month", snap.monthPaise, snap.monthBudgetLimitPaise)
            }
        }

    private fun quotaRow(rv: RemoteViews, r: QuotaRow, label: String, spentPaise: Long, limitPaise: Long) {
        val pct = quotaPct(spentPaise, limitPaise)
        val band = when {
            pct >= 100 -> BudgetBand.OVER
            pct >= BudgetStatus.NEAR_PCT -> BudgetBand.NEAR
            else -> BudgetBand.CALM
        }
        rv.setTextViewText(r.label, label)
        rv.setTextViewText(r.pct, pct.toString())
        val color = when (band) {
            BudgetBand.CALM -> 0xFFFFFFFF.toInt()
            BudgetBand.NEAR -> 0xFFFFD79A.toInt()
            BudgetBand.OVER -> 0xFFF2A0A4.toInt()
        }
        rv.setTextColor(r.pct, color)
        rv.setTextColor(r.sign, color)
        // One ProgressBar per band, visibility-swapped — RemoteViews can tint a bar but cannot swap a
        // gradient drawable, and the gradient is the point.
        val shown = when (band) {
            BudgetBand.CALM -> r.barCalm
            BudgetBand.NEAR -> r.barNear
            BudgetBand.OVER -> r.barOver
        }
        for (bar in intArrayOf(r.barCalm, r.barNear, r.barOver)) {
            rv.setViewVisibility(bar, if (bar == shown) View.VISIBLE else View.GONE)
        }
        // The bar clamps at full while the number keeps counting past 100 (an over week can read 153%).
        rv.setProgressBar(shown, 100, pct.coerceIn(0, 100), false)
    }

    /** Percent of the cap SPENT — matches [BudgetStatus.pct] and, like it, can exceed 100. */
    private fun quotaPct(spentPaise: Long, limitPaise: Long): Int =
        if (limitPaise <= 0L) 0 else ((spentPaise.toDouble() / limitPaise) * 100).toInt()

    /** Labels lengthen once the cell is actually wide enough to hold them. Unknown width → short. */
    private fun quotaIsWide(context: Context, id: Int): Boolean {
        val opts = AppWidgetManager.getInstance(context)?.getAppWidgetOptions(id) ?: return false
        return opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) >= QUOTA_WIDE_DP
    }

    private fun setupViews(context: Context, id: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_setup).apply {
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
        }

    /** The paused card — the whole card is the fix: it opens Accessibility settings, not the app. */
    private fun pausedViews(context: Context, id: Int, stuck: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_paused).apply {
            // Stuck = Settings still shows the switch on, so "turn it back on" would point at a switch that
            // looks fine; the fix is to switch it off and on again.
            if (stuck) setTextViewText(R.id.paused_hint, context.getString(R.string.widget_paused_hint_stuck))
            setOnClickPendingIntent(R.id.widget_root, openAccessibility(context, id))
        }

    // ── PendingIntents ──────────────────────────────────────────────────────────────────────────
    // FLAG_IMMUTABLE is mandatory on API 31+. PendingIntents for different appWidgetIds must differ or
    // they collapse into one (extras alone do NOT disambiguate): the broadcasts use a unique per-id data
    // URI; openApp / openAccessibility use requestCode = appWidgetId, because a URI would break them.

    private fun providerClass(size: WidgetSize): Class<*> = when (size) {
        WidgetSize.SMALL -> WalletWidgetSmall::class.java
        WidgetSize.BIG -> WalletWidgetBig::class.java
        WidgetSize.LARGE -> WalletWidgetLarge::class.java
        WidgetSize.BUDGET -> WalletWidgetBudget::class.java
        WidgetSize.QUOTA -> WalletWidgetQuota::class.java
    }

    /**
     * Opens the app exactly as its launcher icon does — the same MAIN/LAUNCHER intent, NEW_TASK +
     * RESET_TASK_IF_NEEDED — so Android brings the running app forward instead of stacking a second copy
     * on top of it (it only reuses the task when the intent matches the one that started it; the old
     * per-widget data URI never did, so Back from a widget-opened Home landed on another Home). The
     * PendingIntents are told apart by requestCode = appWidgetId, not by a URI. MainActivity keeps its
     * standard launch mode, so the debug-build adb deep links still reach onCreate.
     */
    private fun openApp(context: Context, id: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClass(context, MainActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Accessibility settings, scrolled to our service where the OS honours the hint (see [Permissions]).
     * NO data URI here: this is an IMPLICIT intent, and the Settings app's filter declares no data, so a URI
     * would make it resolve to nothing. The per-id request code keeps the PendingIntents distinct instead.
     */
    private fun openAccessibility(context: Context, id: Int): PendingIntent {
        val intent = Permissions.accessibilitySettings(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun toggleReveal(context: Context, id: Int): PendingIntent =
        broadcast(context, id, WidgetSize.LARGE, BaseWalletWidget.ACTION_TOGGLE_REVEAL, "reveal")

    private fun toggleSpend(context: Context, id: Int, size: WidgetSize): PendingIntent =
        broadcast(context, id, size, BaseWalletWidget.ACTION_TOGGLE_SPEND, "spend")

    private fun refresh(context: Context, id: Int, size: WidgetSize): PendingIntent =
        broadcast(context, id, size, BaseWalletWidget.ACTION_REFRESH, "refresh")

    private fun broadcast(context: Context, id: Int, size: WidgetSize, action: String, slug: String): PendingIntent {
        val intent = Intent(context, providerClass(size)).apply {
            this.action = action
            data = Uri.parse("upiwidget://id/$id/$slug")
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Which words go under the budget widget's number. */
internal enum class BudgetLabel { LEFT, AT_LIMIT, OVER }

/** The budget widget's big number and the label under it. */
internal data class BudgetHeadline(val pct: Int, val label: BudgetLabel)

/**
 * PURE — what the budget widget says, in the same words as Home, Budgets and the nudge:
 *  - under the cap: the percent LEFT, "29% left this month" (the meter's [pctLeft]);
 *  - exactly at it: "0%", "limit reached" (not "over budget");
 *  - past it: the percent USED, the same number Budgets and the Quota widget show, "125% of budget used".
 *    The clamped "0%" above "over budget" read as zero percent over. (The label is kept as short as "left
 *    this month" so it fits the 2×1 cell; the old `widget_budget_over` string is no longer shown but stays,
 *    pinned by TestCopyMarkingTest.)
 */
internal fun budgetHeadline(spentPaise: Long, limitPaise: Long, pctLeft: Int): BudgetHeadline = when {
    limitPaise <= 0L || spentPaise < limitPaise -> BudgetHeadline(pctLeft, BudgetLabel.LEFT)
    spentPaise == limitPaise -> BudgetHeadline(0, BudgetLabel.AT_LIMIT)
    // Same whole-percent as BudgetStatus.pct.
    else -> BudgetHeadline(((spentPaise.toDouble() / limitPaise) * 100).toInt(), BudgetLabel.OVER)
}
