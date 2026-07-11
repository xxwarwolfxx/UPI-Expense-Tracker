package com.goushik.upiwallet.ui.onboarding

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.IconAccessibility
import com.goushik.upiwallet.ui.common.IconBattery
import com.goushik.upiwallet.ui.common.IconSms
import com.goushik.upiwallet.ui.common.rememberCaptureGrants
import com.goushik.upiwallet.ui.theme.IconAccent
import com.goushik.upiwallet.util.Backup
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step {
    WELCOME, PERMISSIONS, RESTRICTED, DETAIL_A11Y, DETAIL_SMS, DETAIL_BATTERY, MODE, BALANCE, IDENTITY, DONE
}

/**
 * The whole first-run flow as a linear step machine. Permission grant state is live (re-checked on
 * resume); the final "Open wallet" persists balances + identity to Room, whose profile row flips the
 * MainActivity gate to the Status screen.
 */
@Composable
fun OnboardingFlow() {
    val ctx = LocalContext.current
    val (grants, refresh) = rememberCaptureGrants()
    var step by remember { mutableStateOf(Step.WELCOME) }

    // Collected entries (kept across step changes within the flow).
    var hdfcRaw by remember { mutableStateOf("") }
    var sbiRaw by remember { mutableStateOf("") }
    var hdfcPaise by remember { mutableStateOf(0L) }
    var sbiPaise by remember { mutableStateOf(0L) }
    var name by remember { mutableStateOf("") }
    var vpas by remember { mutableStateOf(emptyList<String>()) }
    // Phase C: wallet mode. Spend-only is the pre-selected default; choosing it skips the balance step.
    var showBalance by remember { mutableStateOf(false) }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    // Reinstall path: pick a UET-backup.json and restore. A successful import writes a profile whose
    // onboardedAt is set, so MainActivity's gate flips straight to the wallet — no need to advance steps.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            ServiceLocator.appScope.launch {
                val result = runCatching { Backup.restoreFromUri(ctx, ServiceLocator.db, uri) }
                withContext(Dispatchers.Main) {
                    val msg = result.fold(
                        onSuccess = { "Restored ${it.transactions} payments. Welcome back" },
                        onFailure = { it.message ?: "Couldn't restore that file" },
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // System Back steps backward through the flow (WELCOME lets the system exit normally).
    BackHandler(enabled = step != Step.WELCOME) {
        step = when (step) {
            Step.PERMISSIONS -> Step.WELCOME
            Step.RESTRICTED, Step.DETAIL_A11Y, Step.DETAIL_SMS, Step.DETAIL_BATTERY, Step.MODE -> Step.PERMISSIONS
            Step.BALANCE -> Step.MODE
            Step.IDENTITY -> if (showBalance) Step.BALANCE else Step.MODE
            Step.DONE -> Step.IDENTITY
            Step.WELCOME -> Step.WELCOME
        }
    }

    when (step) {
        Step.WELCOME -> WelcomeScreen(
            onGetStarted = { step = Step.PERMISSIONS },
            onRestore = { restoreLauncher.launch(arrayOf("application/json")) },
        )

        Step.PERMISSIONS -> PermissionHubScreen(
            a11yGranted = grants.a11y,
            smsGranted = grants.sms,
            batteryGranted = grants.battery,
            onA11y = { step = Step.DETAIL_A11Y },
            onSms = { step = Step.DETAIL_SMS },
            onBattery = { step = Step.DETAIL_BATTERY },
            onUnlock = { step = Step.RESTRICTED },
            onContinue = { step = Step.MODE },
        )

        Step.MODE -> ModeScreen(
            showBalance = showBalance,
            onSelect = { showBalance = it },
            onContinue = { step = if (showBalance) Step.BALANCE else Step.IDENTITY },
        )

        Step.RESTRICTED -> RestrictedSettingsScreen(
            onBack = { step = Step.PERMISSIONS },
            onOpenAppInfo = { ctx.startActivity(Permissions.appDetails(ctx)) },
            onRecheck = { refresh(); step = Step.PERMISSIONS },
        )

        Step.DETAIL_A11Y -> PermissionDetailScreen(
            title = "Read the payment screen",
            body = "When you pay on GPay, PhonePe, Paytm or CRED, the app reads the confirmation: the " +
                "amount, who you paid, and your bank. It's the only reliable way to catch payments your " +
                "bank doesn't text about.",
            reassurances = listOf(
                "Reads text only, never screenshots",
                "Nothing leaves your phone",
                "Only your UPI apps, nothing else",
            ),
            granted = grants.a11y,
            primaryLabel = "Open Accessibility settings",
            onPrimary = { ctx.startActivity(Permissions.accessibilitySettings()) },
            onBack = { step = Step.PERMISSIONS },
            secondaryLabel = "Blocked? Open app settings",
            onSecondary = { ctx.startActivity(Permissions.appDetails(ctx)) },
            icon = { IconAccessibility(IconAccent) },
        )

        Step.DETAIL_SMS -> PermissionDetailScreen(
            title = "Catch your bank's texts",
            body = "When your bank texts about a payment, whether money in or out, the app reads the amount " +
                "and reference number to confirm captures and de-duplicate them.",
            reassurances = listOf("Bank UPI messages only", "Stays on your phone"),
            granted = grants.sms,
            primaryLabel = "Allow SMS",
            onPrimary = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) },
            onBack = { step = Step.PERMISSIONS },
            secondaryLabel = "Blocked? Open app settings",
            onSecondary = { ctx.startActivity(Permissions.appDetails(ctx)) },
            icon = { IconSms(IconAccent) },
        )

        Step.DETAIL_BATTERY -> PermissionDetailScreen(
            title = "Don't doze the capture",
            body = "Let UPI Expense Tracker keep running in the background so it doesn't miss a payment " +
                "while your screen is off.",
            reassurances = listOf("Used only to stay awake for capture"),
            granted = grants.battery,
            primaryLabel = "Allow background activity",
            onPrimary = { ctx.startActivity(Permissions.batteryExemption(ctx)) },
            onBack = { step = Step.PERMISSIONS },
            icon = { IconBattery(IconAccent) },
        )

        Step.BALANCE -> BalanceScreen(
            initialHdfc = hdfcRaw,
            initialSbi = sbiRaw,
            onContinue = { hp, sp, hr, sr ->
                hdfcPaise = hp; sbiPaise = sp; hdfcRaw = hr; sbiRaw = sr
                step = Step.IDENTITY
            },
        )

        Step.IDENTITY -> IdentityScreen(
            initialName = name,
            initialVpas = vpas,
            onContinue = { n, v -> name = n; vpas = v; step = Step.DONE },
        )

        Step.DONE -> DoneScreen(
            totalPaise = hdfcPaise + sbiPaise,
            showBalance = showBalance,
            onOpen = {
                // Process-scoped so the write completes even as the gate swaps this composable out.
                ServiceLocator.appScope.launch {
                    val repo = ServiceLocator.repository
                    repo.clearAnchors()
                    val now = System.currentTimeMillis()
                    // Spend-only users never entered a balance → write no anchors (the `onboarded` gate keys
                    // off onboardedAt, not anchors). Balance mode anchors both accounts at "now".
                    if (showBalance) {
                        repo.upsertAnchor(BalanceAnchorEntity(Ids.uuid7(), "HDFC", hdfcPaise, now))
                        repo.upsertAnchor(BalanceAnchorEntity(Ids.uuid7(), "SBI", sbiPaise, now))
                    }
                    repo.upsertProfile(
                        UserProfileEntity(
                            displayName = name,
                            ownVpasCsv = vpas.joinToString(","),
                            onboardedAt = now,
                            showBalance = showBalance,
                        ),
                    )
                }
            },
        )
    }
}
