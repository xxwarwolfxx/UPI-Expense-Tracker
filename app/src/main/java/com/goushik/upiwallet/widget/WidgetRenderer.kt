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
import com.goushik.upiwallet.domain.budget.budgetMeter
import com.goushik.upiwallet.util.Money

/**
 * Builds the [RemoteViews] for each widget size from a [WidgetSnapshot]. Pure view-binding — the
 * data load happens upstream (provider onUpdate / the freshness collector). The balance + per-account
 * chips render masked unless [revealed]; the balance eye toggles it. Spend numbers (today/week/month) are
 * shown by default and masked when [spendHidden]; the spend eye toggles that. Just the amount per window —
 * no payment counts. Small carries the spend-hide eye too (so masking is consistent across all sizes); it
 * still has no reload button, keeping its 2×1 cell uncrowded.
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
    ): RemoteViews {
        if (!snap.onboarded) return setupViews(context, appWidgetId)
        return when (size) {
            WidgetSize.SMALL -> small(context, appWidgetId, snap, spendHidden)
            WidgetSize.BIG -> big(context, appWidgetId, snap, spendHidden)
            WidgetSize.LARGE -> large(context, appWidgetId, snap, revealed, spendHidden)
            WidgetSize.BUDGET -> budget(context, appWidgetId, snap)
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
            if (snap.showBalance) {
                setViewVisibility(R.id.balance_hair, View.VISIBLE)
                setViewVisibility(R.id.balance_row, View.VISIBLE)
                setViewVisibility(R.id.balance_chips, View.VISIBLE)
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
            setTextViewText(R.id.budget_pct_num, m.pctLeft.toString())
            setTextColor(R.id.budget_pct_num, pctColor)
            setTextColor(R.id.budget_pct_sign, pctColor)
            setTextViewText(R.id.budget_label, if (m.over) "over budget" else "left this month")
            for (i in budgetTierIds.indices) {
                val isLit = i >= BUDGET_TIERS - m.litTiers   // fill from the bottom of the stack
                setImageViewResource(budgetTierIds[i], if (isLit) litRes else R.drawable.widget_note_ghost)
            }
        }

    private fun setupViews(context: Context, id: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_setup).apply {
            setOnClickPendingIntent(R.id.widget_root, openApp(context, id))
        }

    // ── PendingIntents ──────────────────────────────────────────────────────────────────────────
    // FLAG_IMMUTABLE is mandatory on API 31+. The unique per-id data URI is what disambiguates the
    // PendingIntents — without it, intents for different appWidgetIds collapse into one. Extras alone
    // do NOT disambiguate.

    private fun providerClass(size: WidgetSize): Class<*> = when (size) {
        WidgetSize.SMALL -> WalletWidgetSmall::class.java
        WidgetSize.BIG -> WalletWidgetBig::class.java
        WidgetSize.LARGE -> WalletWidgetLarge::class.java
        WidgetSize.BUDGET -> WalletWidgetBudget::class.java
    }

    private fun openApp(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("upiwidget://id/$id/open")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
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
