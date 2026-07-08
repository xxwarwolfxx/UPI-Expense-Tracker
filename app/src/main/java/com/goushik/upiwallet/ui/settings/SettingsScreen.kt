package com.goushik.upiwallet.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.HealthStore
import com.goushik.upiwallet.ui.common.CaptureDownBanner
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconChevronDown
import com.goushik.upiwallet.ui.common.TogglePill
import com.goushik.upiwallet.ui.common.rememberCaptureGrants
import com.goushik.upiwallet.ui.donate.DonateButton
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Backup
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings & health tab (USER-FLOW §5). Read-only readout + deep-links; AppShell draws the aurora and
 * hosts the bottom nav, so this is just HomeContent's LazyColumn — no WalletBackground/systemBarsPadding.
 * Mutating flows (update balance, edit profile) route out via callbacks; CSV export is a one-shot.
 */
@Composable
fun SettingsScreen(
    onUpdateBalance: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenBudgets: () -> Unit,
) {
    val repo = ServiceLocator.repository
    val txns by repo.observeTransactions().collectAsStateWithLifecycle(emptyList())
    val anchors by repo.observeAnchors().collectAsStateWithLifecycle(emptyList())
    val profile by repo.observeProfile().collectAsStateWithLifecycle(null)
    val budgets by repo.observeBudgets().collectAsStateWithLifecycle(emptyList())
    val (grants, _) = rememberCaptureGrants()
    val ctx = LocalContext.current

    // Restore: let the user pick a UET-backup.json (SAF, no storage permission) and merge it back in.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            ServiceLocator.appScope.launch {
                val result = runCatching { Backup.restoreFromUri(ctx, ServiceLocator.db, uri) }
                withContext(Dispatchers.Main) {
                    val msg = result.fold(
                        onSuccess = { "Restored ${it.transactions} payments from the backup" },
                        onFailure = { it.message ?: "Couldn't restore that file" },
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val available = BalanceCalculator.available(
        anchors, txns,
        ownVpas = profile?.ownVpaSet() ?: emptySet(),
        ownNames = profile?.ownNameSet() ?: emptySet(),
    )
    // Post-onboarding there is always a profile + ≥1 anchor; the flows' initial (null / empty) values
    // just mean Room hasn't emitted yet. Guard so entry never flashes a wrong (even negative) balance.
    val ready = profile != null && anchors.isNotEmpty()
    val showBalance = profile?.showBalance ?: true
    val lastChecked = remember(grants) { HealthStore(ctx).lastCheckedAt() }
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "1.0"
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = topInset + 8.dp,
            bottom = BottomNavHeight + bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary) }

        if (!grants.a11y) {
            item {
                CaptureDownBanner(onFix = { ctx.startActivity(Permissions.accessibilitySettings()) })
            }
        }

        // ── Capture health ──
        item {
            SettingsSection("Capture health") {
                SettingsCard {
                    HealthRow(
                        "Accessibility (capture)", grants.a11y, first = true,
                        onClick = { ctx.startActivity(Permissions.accessibilitySettings()) },
                    )
                    HealthRow(
                        "Bank SMS", grants.sms,
                        onClick = { ctx.startActivity(Permissions.appDetails(ctx)) },
                    )
                    HealthRow(
                        "Battery unrestricted", grants.battery,
                        onClick = { ctx.startActivity(Permissions.batteryExemption(ctx)) },
                    )
                }
                if (lastChecked > 0L) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Checked ${DateTime.ago(lastChecked)}",
                        style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }

        // ── Wallet mode (Phase C) ──
        item {
            val fancyCard by ServiceLocator.uiPrefs.fancyCard.collectAsStateWithLifecycle()
            SettingsSection("Wallet") {
                SettingsCard {
                    ToggleRow(
                        "Show account balance",
                        if (showBalance) "Your available-balance wallet is on" else "Off — just your spending",
                        on = showBalance, first = true,
                        onToggle = { newVal ->
                            profile?.let { p ->
                                ServiceLocator.appScope.launch { repo.upsertProfile(p.copy(showBalance = newVal)) }
                            }
                        },
                    )
                    ToggleRow(
                        "Fancy card",
                        if (fancyCard) "3D tilt, shine and the card flip" else "Off — a simple glass card",
                        on = fancyCard,
                        onToggle = { ServiceLocator.uiPrefs.setFancyCard(it) },
                    )
                }
            }
        }

        // ── Balance (dormant + dimmed in spend-only mode). Adding an account lives on Update balance now. ──
        item {
            SettingsSection("Balance") {
                Box(Modifier.alpha(if (showBalance) 1f else 0.45f)) {
                    SettingsCard {
                        SettingsRow("Available", if (ready) Money.format(available) else "—", first = true)
                        anchors.forEach { a ->
                            SettingsRow(a.accountLabel, Money.format(a.baselinePaise))
                        }
                        SettingsRow("↻  Update balance", onClick = onUpdateBalance, chevron = true)
                    }
                }
            }
        }

        // ── You ──
        item {
            SettingsSection("You") {
                SettingsCard {
                    SettingsRow("Name", profile?.displayName ?: "—", first = true, chevron = true, onClick = onEditProfile)
                    SettingsRow(
                        "UPI IDs", profile?.let { "${it.ownVpaSet().size} linked" } ?: "—",
                        onClick = onEditProfile, chevron = true,
                    )
                }
            }
        }

        // ── Data ──
        item {
            SettingsSection("Data") {
                SettingsCard {
                    SettingsRow(
                        "Export to CSV", subtitle = "${txns.size} transactions", first = true,
                        chevron = true,
                        onClick = { com.goushik.upiwallet.util.CsvExport.share(ctx, txns) },
                    )
                    SettingsRow(
                        "Back up now", subtitle = "Save a copy to your Downloads",
                        chevron = true,
                        onClick = {
                            ServiceLocator.appScope.launch {
                                val ok = runCatching {
                                    Backup.writeToDownloads(ctx, ServiceLocator.db)
                                }.isSuccess
                                withContext(Dispatchers.Main) {
                                    val msg = if (ok) {
                                        "Backup saved to Downloads/${Backup.FILE_NAME}"
                                    } else {
                                        "Couldn't save the backup"
                                    }
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                    SettingsRow(
                        "Restore from a backup", subtitle = "Pick a UET-backup.json file",
                        chevron = true,
                        onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your data lives only on this phone, so uninstalling clears it. A backup is auto-saved " +
                        "to your Downloads and updates as you spend — keep that file (or copy it to Drive) and " +
                        "you can restore everything after reinstalling.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        // ── Budgets (Phase 2) ──
        item {
            val setCount = budgets.count { it.limitPaise > 0L }
            SettingsSection("Budgets") {
                SettingsCard {
                    SettingsRow(
                        "Budgets",
                        value = if (setCount > 0) "$setCount set" else null,
                        subtitle = "Daily, weekly, monthly limits",
                        first = true, chevron = true, onClick = onOpenBudgets,
                    )
                }
            }
        }

        // ── Spending map (opt-in location, Slice C) ──
        item {
            val locOn = ServiceLocator.locationSettings.enabled && Permissions.isLocationGranted(ctx)
            SettingsSection("Spending map") {
                SettingsCard {
                    SettingsRow(
                        "Location pins",
                        value = if (locOn) "On" else "Off",
                        subtitle = "Map where you were when you paid",
                        first = true, chevron = true, onClick = onOpenLocation,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Optional, off by default. Reads your location only at pay-time, rounds it to your " +
                        "neighbourhood (~110 m), keeps it on this phone — never uploaded.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        // ── Support (Phase 3 — the pixelated-you donate button; sits directly on the aurora, no glass card) ──
        item {
            SettingsSection("Support") {
                DonateButton()
                Spacer(Modifier.height(12.dp))
                Text(
                    "This app is free and stays on your phone. If it saved you some taps, a small tip " +
                        "helps keep it going.",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Optional. Tapping opens a secure donate page in your browser — the app itself " +
                        "never handles money.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        // ── About — kept LAST so it sits at the very bottom of Settings ──
        item {
            SettingsSection("About") {
                SettingsCard { SettingsRow("Version", versionName, first = true) }
                Spacer(Modifier.height(12.dp))
                Text(
                    // Fully offline — the app has no INTERNET permission, so this claim is enforced, not aspirational.
                    "Everything stays on your phone — we never connect to your bank, and nothing ever " +
                        "leaves your device.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reads text only — never screenshots.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

// ──────────────────────────────── helpers ────────────────────────────────

/** Section title (titleLarge) hugging its card — grouped in one item so spacedBy doesn't split them. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Glass card over the aurora — blur defaults true (no nested surfaces here). */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassSurface(WalletShapes.large)) { content() }
}

/**
 * Label/value row modeled on DetailRow: hairline divider on top when [!first], label left, optional
 * [value] right under weight, optional [subtitle] under the label, optional trailing [chevron].
 * Whole row is clickable when [onClick] is non-null.
 */
@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    subtitle: String? = null,
    first: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        clickable.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge, color = TextPrimary,
                textAlign = TextAlign.End,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            // chevron-down rotated to point right
            IconChevronDown(TextTertiary, size = 18.dp, modifier = Modifier.rotate(-90f))
        }
    }
}

/** A row with a label/subtitle on the left and a [TogglePill] on the right (wallet mode, etc.). */
@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    on: Boolean,
    first: Boolean = false,
    onToggle: (Boolean) -> Unit,
) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.width(16.dp))
        TogglePill(on = on, onToggle = onToggle)
    }
}

/** Capture-health row: StatusScreen.HealthRow made clickable to its fix deep-link. */
@Composable
private fun HealthRow(label: String, ok: Boolean, first: Boolean = false, onClick: () -> Unit) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        if (ok) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCheck(GreenCredit, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("Healthy", style = MaterialTheme.typography.labelLarge, color = GreenCredit)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(PillShape).background(RedDebit))
                Spacer(Modifier.width(7.dp))
                Text("Off", style = MaterialTheme.typography.labelLarge, color = RedDebit)
            }
        }
    }
}
