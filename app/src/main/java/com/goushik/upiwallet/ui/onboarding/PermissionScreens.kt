package com.goushik.upiwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.common.GhostButton
import com.goushik.upiwallet.ui.common.IconAccessibility
import com.goushik.upiwallet.ui.common.IconBattery
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconLock
import com.goushik.upiwallet.ui.common.IconSms
import com.goushik.upiwallet.ui.common.OnboardingScaffold
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.StepDots
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.util.OemCopy
import com.goushik.upiwallet.util.OemGuide

private val IconViolet = Color(0xFFB9AEF5)

// ─────────────────────── Capture setup, steps 1–4 (the sideload reality) ───────────────────────
//
// Android 13+ only reveals App Info's "⋮ → Allow restricted settings" AFTER the user taps the blocked
// accessibility toggle once and sees the block dialog — titled "App was denied access" on Android 16,
// "Restricted setting" on 13–15 (the copy names both, like web/install.html). So the flow walks that exact
// order: 1 try the switch (the block is EXPECTED) → 2 unlock → 3 turn it on → 4 SMS + battery.
// All four share the journey bar's first dot; the label counts the sub-steps.

@Composable
internal fun CaptureStepDots(n: Int) = StepDots(0, 4, "Setup · step $n of 4")

/** Step 1 — deliberately trip the "App was denied access" dialog so Android reveals the unlock.
 *  One action per moment (design review): the settings button is the ONLY button until they've
 *  been there once; then it recedes to a ghost and a primary Next leads on. */
@Composable
fun TryCaptureScreen(
    oem: OemCopy,
    onOpenA11y: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    var opened by rememberSaveable { mutableStateOf(false) }
    OnboardingScaffold(
        onBack = onBack,
        top = { CaptureStepDots(1) },
        footer = {
            if (!opened) {
                PrimaryButton("Open Accessibility settings", { opened = true; onOpenA11y() })
            } else {
                GhostButton("Open Accessibility settings again", onOpenA11y)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Next", onNext)
            }
        },
    ) {
        FeatureTile { IconAccessibility(IconViolet) }
        Spacer(Modifier.height(14.dp))
        Text("Try the capture switch", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Android locks the capture switch for sideloaded apps. Tapping it once is what starts the unlock.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(18.dp))
        NumStep(1, "Tap Open Accessibility settings below.")
        NumStep(2, "Find “${OemGuide.SERVICE_LABEL}” and tap it.")
        NumStep(
            3,
            "Android will say “App was denied access” (on older Androids: “Restricted setting”). " +
                "That's expected. Tap Close.",
        )
        NumStep(4, "Come back here and tap Next.")
        Spacer(Modifier.height(14.dp))
        PathLine(oem.accessPath)
        oem.a11yNote?.let {
            Spacer(Modifier.height(14.dp))
            OemNoteCard(it)
        }
    }
}


/** Step 4 — the two remaining switches; accessibility is already on when this renders. */
@Composable
fun CaptureRestScreen(
    smsGranted: Boolean,
    batteryGranted: Boolean,
    onSms: () -> Unit,
    onBattery: () -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        top = { CaptureStepDots(4) },
        footer = {
            PrimaryButton("Continue", onContinue, enabled = smsGranted)
            if (!smsGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Turn on Bank SMS to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Text("Two more switches", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Capture is on. These two round it out. You can change them later in Settings.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        PermissionRow("Bank SMS", "Catches bank texts & money received", true, smsGranted, onSms) {
            IconSms(IconViolet)
        }
        HorizontalDivider(color = HairlineColor)
        PermissionRow("Battery", "Keep running in the background", false, batteryGranted, onBattery) {
            IconBattery(IconViolet)
        }
    }
}

/** The Settings breadcrumb for THIS phone's skin — small, under the step body. */
@Composable
internal fun PathLine(path: List<String>) {
    Text(
        path.joinToString("  →  "),
        style = MaterialTheme.typography.bodySmall, color = TextTertiary,
    )
}

/** One OEM gotcha (Auto Blocker, revoke-on-reboot…), amber-tinted so it reads as a heads-up. */
@Composable
internal fun OemNoteCard(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(WarnColor.copy(alpha = 0.08f))
            .border(1.dp, WarnColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", style = MaterialTheme.typography.labelLarge, color = WarnColor)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isPrimary: Boolean,
    granted: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                .background(Violet500.copy(alpha = 0.18f))
                .border(1.dp, HairlineColor, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                if (isPrimary) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "PRIMARY",
                        style = MaterialTheme.typography.labelSmall,
                        color = IconViolet,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Violet500.copy(alpha = 0.25f))
                            .border(1.dp, Violet500.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        StatusBadge(granted)
    }
}

@Composable
private fun StatusBadge(granted: Boolean) {
    if (granted) {
        Row(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(GreenCredit.copy(alpha = 0.13f))
                .border(1.dp, GreenCredit.copy(alpha = 0.40f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCheck(GreenCredit, size = 12.dp)
            Spacer(Modifier.width(4.dp))
            Text("On", style = MaterialTheme.typography.labelMedium, color = GreenCredit)
        }
    } else {
        Text(
            "Set up",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x0DFFFFFF))
                .border(1.dp, HairlineColor, RoundedCornerShape(999.dp))
                .padding(horizontal = 11.dp, vertical = 5.dp),
        )
    }
}

// ──────────────── Step 2 — restricted-settings unlock (revealed by step 1's block) ────────────────

@Composable
fun RestrictedSettingsScreen(
    oem: OemCopy,
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onDone: () -> Unit,
) {
    var opened by rememberSaveable { mutableStateOf(false) }
    OnboardingScaffold(
        onBack = onBack,
        top = { CaptureStepDots(2) },
        footer = {
            if (!opened) {
                PrimaryButton("Open app settings", { opened = true; onOpenAppInfo() })
            } else {
                GhostButton("Open app settings again", onOpenAppInfo)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Done", onDone)
            }
        },
    ) {
        FeatureTile { IconLock(IconViolet, size = 24.dp) }
        Spacer(Modifier.height(14.dp))
        Text("Allow restricted settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "That “App was denied access” (or “Restricted setting”) message unlocked a hidden menu " +
                "option. Now:",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(18.dp))
        NumStep(1, "Tap Open app settings below.")
        NumStep(2, "Tap ⋮ (top-right) → Allow restricted settings.")
        NumStep(3, "Confirm with your PIN / fingerprint.")
        NumStep(4, "Come back here and tap Done.")
        Spacer(Modifier.height(16.dp))
        MenuMock()
        Spacer(Modifier.height(12.dp))
        Text(
            "Don't see “Allow restricted settings” in the menu? Go back to the previous step, " +
                "tap the capture switch until that message shows, then return here.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary,
        )
        oem.restrictedNote?.let {
            Spacer(Modifier.height(14.dp))
            OemNoteCard(it)
        }
    }
}

@Composable
private fun NumStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(999.dp))
                .background(Color(0x12FFFFFF)).border(1.dp, HairlineColor, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelLarge, color = TextSecondary) }
        Spacer(Modifier.width(13.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun MenuMock() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E0A26)).border(1.dp, HairlineColor, RoundedCornerShape(14.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("App info", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            Text("⋮", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        HorizontalDivider(color = HairlineColor)
        Text(
            "App details in store",
            style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        )
        Text(
            "Allow restricted settings",
            style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
            modifier = Modifier.fillMaxWidth().background(Violet500.copy(alpha = 0.22f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

// ─────────────────── Per-permission detail (mockup screen 4, reused) ───────────────────

@Composable
fun PermissionDetailScreen(
    title: String,
    body: String,
    reassurances: List<String>,
    granted: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    top: (@Composable ColumnScope.() -> Unit)? = null,
    path: List<String>? = null,
    oemNote: String? = null,
    icon: @Composable () -> Unit,
) {
    OnboardingScaffold(
        onBack = onBack,
        top = top,
        footer = {
            if (granted) {
                PrimaryButton("Done", onDone ?: onBack)
            } else {
                PrimaryButton(primaryLabel, onPrimary)
                if (secondaryLabel != null && onSecondary != null) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton(secondaryLabel, onSecondary)
                }
            }
        },
    ) {
        FeatureTile { icon() }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        if (path != null) {
            Spacer(Modifier.height(12.dp))
            PathLine(path)
        }
        Spacer(Modifier.height(18.dp))
        reassurances.forEach {
            ReassureRow(it)
            Spacer(Modifier.height(9.dp))
        }
        oemNote?.let {
            Spacer(Modifier.height(4.dp))
            OemNoteCard(it)
            Spacer(Modifier.height(9.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (granted) EnabledStrip() else WaitingStrip()
    }
}

@Composable
private fun ReassureRow(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0x0AFFFFFF))
            .border(1.dp, HairlineColor, RoundedCornerShape(999.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(999.dp)).background(GreenCredit))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun WaitingStrip() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x0AFFFFFF))
            .border(1.dp, HairlineColor, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconViolet)
        Spacer(Modifier.width(11.dp))
        Text(
            "Waiting for you to switch it on… we'll detect it automatically.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        )
    }
}

@Composable
private fun EnabledStrip() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(GreenCredit.copy(alpha = 0.10f))
            .border(1.dp, GreenCredit.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCheck(GreenCredit, size = 16.dp)
        Spacer(Modifier.width(11.dp))
        Text("Switched on, you're good.", style = MaterialTheme.typography.bodyMedium, color = GreenCredit)
    }
}

@Composable
private fun FeatureTile(icon: @Composable () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
            .background(Violet500.copy(alpha = 0.20f)).border(1.dp, HairlineColor, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
