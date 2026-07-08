package com.goushik.upiwallet

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.widget.RevealRegistry
import com.goushik.upiwallet.widget.WidgetSnapshot
import com.goushik.upiwallet.widget.WidgetUpdater
import com.goushik.upiwallet.work.HealthCheckWorker
import com.goushik.upiwallet.work.ReconcileWorker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class UpiWalletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ReconcileWorker.schedule(this)
        HealthCheckWorker.schedule(this)
        startWidgetSync()
    }

    /**
     * Home-screen widgets don't observe Room, so keep them fresh from ONE process-scoped collector
     * over the same three flows Home uses → push to every placed widget. It also doubles as the
     * re-mask safety net: a fresh process starts with an empty [RevealRegistry], so its first push
     * renders the balance masked. The screen-off receiver re-masks explicitly while the process is up.
     */
    private fun startWidgetSync() {
        val repo = ServiceLocator.repository
        ServiceLocator.appScope.launch {
            combine(
                repo.observeTransactions(),
                repo.observeAnchors(),
                repo.observeProfile(),
                repo.observeBudgets(),
            ) { txns, anchors, profile, budgets ->
                WidgetSnapshot.build(txns, anchors, profile, budgets, System.currentTimeMillis())
            }.collect { snap -> WidgetUpdater.push(this@UpiWalletApp, snap) }
        }

        // Reveal is ephemeral: re-mask the balance when the screen turns off / the phone locks.
        // ACTION_SCREEN_OFF can't be a manifest receiver, so register it dynamically (process-scoped).
        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (RevealRegistry.clearAll()) {
                    ServiceLocator.appScope.launch { runCatching { WidgetUpdater.loadAndPush(context) } }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
