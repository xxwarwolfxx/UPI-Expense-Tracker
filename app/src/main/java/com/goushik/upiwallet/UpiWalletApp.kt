package com.goushik.upiwallet

import android.app.Application
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
        com.goushik.upiwallet.util.Dbg.init(this) // before anything logs
        ServiceLocator.init(this)
        RevealRegistry.init(this) // load persisted widget eye state before any widget renders
        ReconcileWorker.schedule(this)
        HealthCheckWorker.schedule(this)
        startWidgetSync()
    }

    /**
     * Home-screen widgets don't observe Room, so keep them fresh from ONE process-scoped collector over
     * the same three flows Home uses → push to every placed widget. Eye state is persisted
     * ([RevealRegistry]), so each push renders the user's last saved choice (masked-by-default until they
     * reveal it; whatever they set then sticks — no reset on lock/unlock).
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
    }
}
