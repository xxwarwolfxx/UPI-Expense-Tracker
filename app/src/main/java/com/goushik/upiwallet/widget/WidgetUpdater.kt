package com.goushik.upiwallet.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.goushik.upiwallet.di.ServiceLocator

/**
 * Pushes a snapshot to every placed widget of all three providers. Called by the reactive freshness
 * collector (with a snapshot already built from the Room flows) and by the screen-off receiver
 * (via [loadAndPush]). Reveal state is read per-id so a re-push never un-reveals a live one.
 */
object WidgetUpdater {
    private val providers = listOf(
        WidgetSize.SMALL to WalletWidgetSmall::class.java,
        WidgetSize.BIG to WalletWidgetBig::class.java,
        WidgetSize.LARGE to WalletWidgetLarge::class.java,
        WidgetSize.BUDGET to WalletWidgetBudget::class.java,
    )

    fun push(context: Context, snap: WidgetSnapshot) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        for ((size, cls) in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls)) ?: continue
            for (id in ids) {
                mgr.updateAppWidget(
                    id,
                    WidgetRenderer.build(
                        context, size, id, snap,
                        revealed = RevealRegistry.isRevealed(id),
                        spendHidden = RevealRegistry.isSpendHidden(id),
                    ),
                )
            }
        }
    }

    suspend fun loadAndPush(context: Context) = push(context, WidgetData.load(ServiceLocator.repository))
}
