package com.goushik.upiwallet.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.CaptureWatch

/**
 * Pushes a snapshot to every placed widget of all five providers. Called by the reactive freshness
 * collector (with a snapshot already built from the Room flows), by the 15-minute sweep and by the
 * capture-watch tick (via [loadAndPush]). Reveal state is read per-id so a re-push never un-reveals a
 * live one. Whether capture is paused is read ONCE per push, so every widget flips together.
 */
object WidgetUpdater {
    private val providers = listOf(
        WidgetSize.SMALL to WalletWidgetSmall::class.java,
        WidgetSize.BIG to WalletWidgetBig::class.java,
        WidgetSize.LARGE to WalletWidgetLarge::class.java,
        WidgetSize.BUDGET to WalletWidgetBudget::class.java,
        WidgetSize.QUOTA to WalletWidgetQuota::class.java,
    )

    fun push(context: Context, snap: WidgetSnapshot) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val paused = CaptureWatch.isPaused(context)
        val stuck = paused && CaptureWatch.isStuck(context)
        for ((size, cls) in providers) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls)) ?: continue
            for (id in ids) {
                mgr.updateAppWidget(
                    id,
                    WidgetRenderer.build(
                        context, size, id, snap,
                        revealed = RevealRegistry.isRevealed(id),
                        spendHidden = RevealRegistry.isSpendHidden(id),
                        capturePaused = paused,
                        captureStuck = stuck,
                    ),
                )
            }
        }
    }

    suspend fun loadAndPush(context: Context) = push(context, WidgetData.load(ServiceLocator.repository))
}
