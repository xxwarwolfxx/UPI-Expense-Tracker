package com.goushik.upiwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

private val IconViolet = Color(0xFFB9AEF5)

// ───────────────────────── Permissions hub (mockup screen 2) ─────────────────────────

@Composable
fun PermissionHubScreen(
    a11yGranted: Boolean,
    smsGranted: Boolean,
    batteryGranted: Boolean,
    onA11y: () -> Unit,
    onSms: () -> Unit,
    onBattery: () -> Unit,
    onUnlock: () -> Unit,
    onContinue: () -> Unit,
) {
    val canContinue = a11yGranted && smsGranted
    OnboardingScaffold(
        top = { StepDots(0, 4, "Setup") },
        footer = {
            PrimaryButton("Continue", onContinue, enabled = canContinue)
            if (!canContinue) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Turn on Accessibility & SMS to continue",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Text("Switch on capture", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Three switches let the app see your payments. Change them anytime in Settings.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        UnlockBanner(onUnlock)
        Spacer(Modifier.height(6.dp))
        PermissionRow("Accessibility", "Reads the payment screen, text only", true, a11yGranted, onA11y) {
            IconAccessibility(IconViolet)
        }
        HorizontalDivider(color = HairlineColor)
        PermissionRow("Bank SMS", "Catches bank texts & money received", false, smsGranted, onSms) {
            IconSms(IconViolet)
        }
        HorizontalDivider(color = HairlineColor)
        PermissionRow("Battery", "Keep running in the background", false, batteryGranted, onBattery) {
            IconBattery(IconViolet)
        }
    }
}

@Composable
private fun UnlockBanner(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Violet500.copy(alpha = 0.10f))
            .border(1.dp, Violet500.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconLock(IconViolet, size = 20.dp)
        Spacer(Modifier.width(11.dp))
        Text(
            "One-time: Android hides these for sideloaded apps.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("Unlock →", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
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

// ──────────────────── Restricted-settings explainer (mockup screen 3) ────────────────────

@Composable
fun RestrictedSettingsScreen(
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRecheck: () -> Unit,
) {
    OnboardingScaffold(
        onBack = onBack,
        footer = {
            PrimaryButton("Open app settings", onOpenAppInfo)
            Spacer(Modifier.height(10.dp))
            GhostButton("I've done this, re-check", onRecheck)
        },
    ) {
        FeatureTile { IconLock(IconViolet, size = 24.dp) }
        Spacer(Modifier.height(14.dp))
        Text("Allow restricted settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Because the app is sideloaded, Android locks Accessibility & SMS until you allow them once. Quick:",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(18.dp))
        NumStep(1, "Tap Open app settings below.")
        NumStep(2, "Tap ⋮ (top-right) → Allow restricted settings.")
        NumStep(3, "Confirm with your PIN / fingerprint.")
        NumStep(4, "Come back, and we'll handle the rest.")
        Spacer(Modifier.height(16.dp))
        MenuMock()
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
    icon: @Composable () -> Unit,
) {
    OnboardingScaffold(
        onBack = onBack,
        footer = {
            if (granted) {
                PrimaryButton("Done", onBack)
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
        Spacer(Modifier.height(18.dp))
        reassurances.forEach {
            ReassureRow(it)
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
