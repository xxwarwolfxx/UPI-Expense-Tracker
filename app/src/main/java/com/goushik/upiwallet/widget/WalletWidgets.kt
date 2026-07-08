package com.goushik.upiwallet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.goushik.upiwallet.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Shared base for the three home-screen widgets. Each subclass is a no-arg, manifest-instantiated
 * provider that pins its [size]. onUpdate loads a snapshot off the main thread ([goAsync] + the
 * process-scoped IO scope) and binds it — the try/finally guarantees `finish()` on every path.
 *
 * onReceive handles the three tap actions for EVERY size (the per-widget PendingIntents target whichever
 * provider class drew the widget): the balance eye (large only), the spend-hide eye (all sizes), and the
 * manual reload glyph (big/large). Reload repaints every placed widget; the toggles repaint just the tapped one.
 */
abstract class BaseWalletWidget(private val size: WidgetSize) : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                val snap = WidgetData.load(ServiceLocator.repository)
                for (id in ids) mgr.updateAppWidget(id, render(context, id, snap))
            } catch (_: Throwable) {
                // Fail-silent: keep the last rendered views rather than crash the widget host.
            } finally {
                pending.finish()
            }
        }
    }

    /** Re-render on resize so content reflows to the new cell size. */
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        onUpdate(context, mgr, intArrayOf(id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_REVEAL, ACTION_TOGGLE_SPEND -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                if (intent.action == ACTION_TOGGLE_REVEAL) RevealRegistry.toggle(id)
                else RevealRegistry.toggleSpendHidden(id)
                val pending = goAsync()
                ServiceLocator.appScope.launch {
                    try {
                        val snap = WidgetData.load(ServiceLocator.repository)
                        AppWidgetManager.getInstance(context).updateAppWidget(id, render(context, id, snap))
                    } catch (_: Throwable) {
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_REFRESH -> {
                // Manual "refresh now" — repaint every placed widget from a fresh snapshot (free: only on tap).
                val pending = goAsync()
                ServiceLocator.appScope.launch {
                    try { WidgetUpdater.loadAndPush(context) } catch (_: Throwable) {} finally { pending.finish() }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun render(context: Context, id: Int, snap: WidgetSnapshot) =
        WidgetRenderer.build(
            context, size, id, snap,
            revealed = RevealRegistry.isRevealed(id),
            spendHidden = RevealRegistry.isSpendHidden(id),
        )

    companion object {
        const val ACTION_TOGGLE_REVEAL = "com.goushik.upiwallet.widget.action.TOGGLE_REVEAL"
        const val ACTION_TOGGLE_SPEND = "com.goushik.upiwallet.widget.action.TOGGLE_SPEND"
        const val ACTION_REFRESH = "com.goushik.upiwallet.widget.action.REFRESH"
    }
}

class WalletWidgetSmall : BaseWalletWidget(WidgetSize.SMALL)

class WalletWidgetBig : BaseWalletWidget(WidgetSize.BIG)

/** The balance widget — large is the only one with a balance (and its reveal eye). */
class WalletWidgetLarge : BaseWalletWidget(WidgetSize.LARGE)

/** The monthly-budget money-stack meter — % of the cap left, no rupees. */
class WalletWidgetBudget : BaseWalletWidget(WidgetSize.BUDGET)
