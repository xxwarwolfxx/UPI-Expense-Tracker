package com.goushik.upiwallet.ui.onboarding

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.goushik.upiwallet.util.OemGuide
import com.goushik.upiwallet.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step {
    WELCOME,
    A11Y_TRY, // step 1: tap the blocked accessibility toggle — the block is EXPECTED (it reveals the unlock)
    A11Y_UNLOCK, // step 2: App Info → ⋮ → Allow restricted settings (only visible after step 1's block)
    A11Y_ENABLE, // step 3: back to Accessibility — the switch works now
    CAPTURE_REST, // step 4: SMS + battery
    DETAIL_SMS, DETAIL_BATTERY, // reached from step 4's rows
    MODE, BALANCE, IDENTITY, DONE,
}

private val A11Y_STEPS = setOf(Step.A11Y_TRY, Step.A11Y_UNLOCK, Step.A11Y_ENABLE)

/**
 * The whole first-run flow as a linear step machine. Permission grant state is live (re-checked on
 * resume); the final "Open wallet" persists balances + identity to Room, whose profile row flips the
 * MainActivity gate to the Status screen.
 *
 * Everything the user has entered, and the step they're on, is saveable: the unlock step sends them to
 * App info to confirm with their PIN, which is exactly when Android likes to kill a backgrounded app, and
 * a rotation used to drop them back on Welcome with the mode, balances, name and UPI IDs gone.
 *
 * Restoring a backup from Welcome does NOT jump to Home any more. A reinstall resets Android's
 * "Allow restricted settings" unlock and the SMS permission, and nothing after onboarding teaches the
 * unlock, so the restore brings the data back with onboardedAt held empty and the flow carries on at the
 * capture steps; finishing them sets onboardedAt and opens the wallet. "A restore is in progress" is read
 * from the database — a profile row that exists without onboardedAt (a fresh install has no row until the
 * end) — not from screen state, so it survives the app being killed during the unlock.
 */
@Composable
fun OnboardingFlow() {
    val ctx = LocalContext.current
    val (grants, refresh) = rememberCaptureGrants()
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }

    // Collected entries (kept across step changes within the flow, and across rotation/process death).
    var hdfcRaw by rememberSaveable { mutableStateOf("") }
    var sbiRaw by rememberSaveable { mutableStateOf("") }
    var hdfcPaise by rememberSaveable { mutableStateOf(0L) }
    var sbiPaise by rememberSaveable { mutableStateOf(0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var vpas by rememberSaveable(stateSaver = UpiIdListSaver) { mutableStateOf(emptyList<String>()) }
    // Phase C: wallet mode. Spend-only is the pre-selected default; choosing it skips the balance step.
    var showBalance by rememberSaveable { mutableStateOf(false) }

    val profileFlow = remember { ServiceLocator.repository.observeProfile() }
    val profile by profileFlow.collectAsStateWithLifecycle(initialValue = null)
    val restoreInProgress = profile != null && profile?.onboardedAt == null

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    // Reinstall path: pick a UET-backup file and restore. The data comes back but setup continues at the
    // capture steps it still needs (see the KDoc); with capture already fully on, it finishes right away.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            ServiceLocator.appScope.launch {
                val result = runCatching {
                    Backup.restoreFromUri(ctx, ServiceLocator.db, uri, holdOnboarding = true)
                }
                withContext(Dispatchers.Main) {
                    val msg = result.fold(
                        onSuccess = { r -> Backup.restoreSummary(r) },
                        onFailure = { it.message ?: "Couldn't restore that file" },
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    if (result.isSuccess) {
                        val a11y = Permissions.isA11yEnabled(ctx)
                        val sms = Permissions.isSmsGranted(ctx)
                        when {
                            !a11y -> step = Step.A11Y_TRY
                            !sms -> step = Step.CAPTURE_REST
                            else -> finishRestoredSetup()
                        }
                    }
                }
            }
        }
    }

    // Steps 1–3 exist to get accessibility on; the moment it IS on, jump to step 4 — whichever screen
    // the user is parked on (their OEM may never block, or they unlocked earlier). One-shot so pressing
    // Back to revisit step 3 doesn't bounce them forward again.
    var a11yAutoAdvanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(grants.a11y, step) {
        if (grants.a11y && !a11yAutoAdvanced && step in A11Y_STEPS) {
            a11yAutoAdvanced = true
            step = Step.CAPTURE_REST
        }
    }

    val oem = remember { OemGuide.current() }

    // System Back steps backward through the flow (WELCOME lets the system exit normally).
    BackHandler(enabled = step != Step.WELCOME) {
        step = when (step) {
            Step.A11Y_TRY -> Step.WELCOME
            Step.A11Y_UNLOCK -> Step.A11Y_TRY
            Step.A11Y_ENABLE -> Step.A11Y_UNLOCK
            Step.CAPTURE_REST -> Step.A11Y_ENABLE
            Step.DETAIL_SMS, Step.DETAIL_BATTERY, Step.MODE -> Step.CAPTURE_REST
            Step.BALANCE -> Step.MODE
            Step.IDENTITY -> if (showBalance) Step.BALANCE else Step.MODE
            Step.DONE -> Step.IDENTITY
            Step.WELCOME -> Step.WELCOME
        }
    }

    when (step) {
        Step.WELCOME -> WelcomeScreen(
            onGetStarted = { step = Step.A11Y_TRY },
            onRestore = { restoreLauncher.launch(arrayOf("application/json")) },
        )

        Step.A11Y_TRY -> TryCaptureScreen(
            oem = oem,
            onOpenA11y = { Permissions.openAccessibilitySettings(ctx) },
            onNext = { step = Step.A11Y_UNLOCK },
            onBack = { step = Step.WELCOME },
        )

        Step.A11Y_UNLOCK -> RestrictedSettingsScreen(
            oem = oem,
            onBack = { step = Step.A11Y_TRY },
            onOpenAppInfo = { ctx.startActivity(Permissions.appDetails(ctx)) },
            onDone = { refresh(); step = Step.A11Y_ENABLE },
        )

        Step.A11Y_ENABLE -> PermissionDetailScreen(
            title = "Turn on capture",
            body = "The switch works now. When you pay on GPay, PhonePe, Paytm or CRED, the app reads " +
                "the confirmation screen: the amount, who you paid, which bank.",
            reassurances = listOf(
                "Reads text only, never screenshots",
                "Nothing leaves your phone",
                "Only your UPI apps, nothing else",
            ),
            granted = grants.a11y,
            primaryLabel = "Open Accessibility settings",
            onPrimary = { Permissions.openAccessibilitySettings(ctx) },
            onBack = { step = Step.A11Y_UNLOCK },
            secondaryLabel = "Still blocked? Back to the unlock",
            onSecondary = { step = Step.A11Y_UNLOCK },
            onDone = { step = Step.CAPTURE_REST },
            top = { CaptureStepDots(3) },
            path = oem.accessPath,
            oemNote = oem.a11yNote,
            icon = { IconAccessibility(IconAccent) },
        )

        Step.CAPTURE_REST -> CaptureRestScreen(
            smsGranted = grants.sms,
            batteryGranted = grants.battery,
            onSms = { step = Step.DETAIL_SMS },
            onBattery = { step = Step.DETAIL_BATTERY },
            // A restore already brought back the mode, balances, name and UPI IDs — asking for them
            // again would overwrite the restored ones (and DONE clears the accounts), so it ends here.
            onContinue = { if (restoreInProgress) finishRestoredSetup() else step = Step.MODE },
        )

        Step.MODE -> ModeScreen(
            showBalance = showBalance,
            onSelect = { showBalance = it },
            onContinue = { step = if (showBalance) Step.BALANCE else Step.IDENTITY },
        )

        Step.DETAIL_SMS -> PermissionDetailScreen(
            title = "Catch your bank's texts",
            body = "When your bank texts about a payment, whether money in or out, the app reads the amount " +
                "and reference number to confirm captures and de-duplicate them.",
            reassurances = listOf("Bank UPI messages only", "Stays on your phone"),
            granted = grants.sms,
            primaryLabel = "Allow SMS",
            onPrimary = { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) },
            onBack = { step = Step.CAPTURE_REST },
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
            onBack = { step = Step.CAPTURE_REST },
            oemNote = oem.batteryNote,
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
                    // Belt and braces: a restore in progress must never reach the fresh-setup write below,
                    // which clears the accounts and replaces the profile.
                    val existing = repo.profile()
                    if (existing != null && existing.onboardedAt == null) {
                        repo.upsertProfile(existing.copy(onboardedAt = System.currentTimeMillis()))
                        return@launch
                    }
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

/**
 * Finish a Welcome-screen restore: stamp onboardedAt on the restored profile (everything else in it is
 * the backup's), which flips MainActivity's gate to the wallet. Process-scoped for the same reason as
 * DONE's write.
 */
private fun finishRestoredSetup() {
    ServiceLocator.appScope.launch {
        val repo = ServiceLocator.repository
        val p = repo.profile()
            ?: UserProfileEntity(displayName = "", ownVpasCsv = "", onboardedAt = null, showBalance = false)
        if (p.onboardedAt == null) repo.upsertProfile(p.copy(onboardedAt = System.currentTimeMillis()))
    }
}
