package com.goushik.upiwallet

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.capture.A11yCaptureService
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.SampleData
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.nav.AppShell
import com.goushik.upiwallet.ui.onboarding.OnboardingFlow
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Gate { LOADING, ONBOARDING, ONBOARDED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebugIntent()
        setContent {
            UPIWalletTheme {
                val repo = ServiceLocator.repository
                val gateFlow = remember {
                    repo.observeProfile().map { p ->
                        if (p?.onboardedAt != null) Gate.ONBOARDED else Gate.ONBOARDING
                    }
                }
                val gate by gateFlow.collectAsStateWithLifecycle(initialValue = Gate.LOADING)
                when (gate) {
                    Gate.LOADING -> WalletBackground {}     // brief blank dark; avoids onboarding flash
                    Gate.ONBOARDING -> OnboardingFlow()
                    Gate.ONBOARDED -> AppShell()
                }
            }
        }
    }

    /**
     * Debug-only adb hooks (no-op on a non-debuggable build, so they can never fire on a release artifact):
     *   seed   — fill the app with sample data WITHOUT tapping through onboarding:
     *     adb shell am start -n com.goushik.upiwallet/.MainActivity -d "upiwallet://debug/seed"
     *     ([SampleData.seed] also writes a sample profile, so the gate routes straight to Home.)
     *   learn  — toggle Phase-4 capture learning mode (harvest PhonePe/Paytm/CRED confirm-sheet trees):
     *     adb shell am start -n com.goushik.upiwallet/.MainActivity -d "upiwallet://debug/learn"
     */
    private fun handleDebugIntent() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        when (intent?.data?.toString()) {
            "upiwallet://debug/seed" -> ServiceLocator.appScope.launch { SampleData.seed(ServiceLocator.db) }
            "upiwallet://debug/learn" -> {
                A11yCaptureService.learningMode = !A11yCaptureService.learningMode
            }
        }
    }
}
