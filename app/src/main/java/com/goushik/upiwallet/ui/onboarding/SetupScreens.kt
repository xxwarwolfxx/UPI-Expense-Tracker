package com.goushik.upiwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.GhostButton
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconInfo
import com.goushik.upiwallet.ui.common.IconInsights
import com.goushik.upiwallet.ui.common.IconWallet
import com.goushik.upiwallet.ui.common.OnboardingScaffold
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.RemovableChip
import com.goushik.upiwallet.ui.common.StepDots
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.SurfaceColor
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.withPendingUpiId

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }

/**
 * A list of UPI IDs kept across rotation and process death. Always saved as a list, an empty one too:
 * `listSaver` saves an empty list as nothing, and rememberSaveable then restores the state as null.
 */
internal val UpiIdListSaver: Saver<List<String>, ArrayList<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it },
)

// ──────────────────────── What do you want to see? (Phase C) ────────────────────────

/** The new onboarding step: pick spend-only vs. balance mode. Spend-only skips the balance step entirely.
 *  [showBalance] = currently-selected mode; pre-selected to spend-only (the simplest glance). */
@Composable
fun ModeScreen(showBalance: Boolean, onSelect: (Boolean) -> Unit, onContinue: () -> Unit) {
    OnboardingScaffold(
        top = { StepDots(1, 4, "What you'll see") },
        footer = { PrimaryButton("Continue", onContinue) },
    ) {
        Text("What do you\nwant to see?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(
            "This app's heart is showing where your money goes. Want your live balance too, or just your spending?",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(28.dp))
        ModeChoice(
            selected = !showBalance,
            title = "Just my spending",
            body = "See only how much you spend through UPI, the simplest way to glance and reflect.",
            onClick = { onSelect(false) },
        ) { IconInsights(Color.White, size = 22.dp) }
        Spacer(Modifier.height(14.dp))
        ModeChoice(
            selected = showBalance,
            title = "My balance too",
            body = "A wallet you glance at: your available balance, kept live by subtracting every payment.",
            onClick = { onSelect(true) },
        ) { IconWallet(Color.White, size = 22.dp) }
        Spacer(Modifier.height(18.dp))
        Text(
            "You can switch anytime in Settings.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModeChoice(
    selected: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = if (selected) 0.09f else 0.05f))
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.40f) else HairlineColor, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                .background(if (selected) Violet500.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.06f))
                .border(1.dp, if (selected) Violet500.copy(alpha = 0.6f) else HairlineColor, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), color = TextPrimary,
            )
            Spacer(Modifier.height(5.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(999.dp)).background(Violet500),
                contentAlignment = Alignment.Center,
            ) { IconCheck(Color.White, size = 13.dp) }
        }
    }
}

// ──────────────────────── Starting balances (mockup screen 5) ────────────────────────

@Composable
fun BalanceScreen(
    initialHdfc: String,
    initialSbi: String,
    onContinue: (hdfcPaise: Long, sbiPaise: Long, hdfcRaw: String, sbiRaw: String) -> Unit,
) {
    // What's typed here is saveable: a rotation, a theme switch or the app being killed in the background
    // must not empty the boxes before Continue hands them up.
    var hdfc by rememberSaveable { mutableStateOf(initialHdfc) }
    var sbi by rememberSaveable { mutableStateOf(initialSbi) }
    val hp = Money.parsePaise(hdfc)
    val sp = Money.parsePaise(sbi)
    val valid = hp != null && hp >= 0 && sp != null && sp >= 0

    OnboardingScaffold(
        top = { StepDots(2, 4, "Balance") },
        footer = { PrimaryButton("Continue", { onContinue(hp!!, sp!!, hdfc, sbi) }, enabled = valid) },
    ) {
        Text("What's in your accounts?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Copy the balance from your bank app. We track every payment from here, and you can update it anytime the number drifts.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(22.dp))
        FieldLabel("HDFC")
        WalletTextField(
            hdfc, { hdfc = moneyFilter(it) }, prefix = "₹", placeholder = "0",
            keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(18.dp))
        FieldLabel("SBI")
        WalletTextField(
            sbi, { sbi = moneyFilter(it) }, prefix = "₹", placeholder = "0",
            keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(14.dp))
        HintRow("A starting point, not a login. We never connect to your bank.")
    }
}

// ─────────────────────────── Identity / self (mockup screen 6) ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityScreen(
    initialName: String,
    initialVpas: List<String>,
    onContinue: (name: String, vpas: List<String>) -> Unit,
) {
    // Saveable, like the balance step: the hint below sends the user off to GPay to look up their IDs,
    // which is when Android likes to kill a backgrounded app (and a rotation would drop them too).
    var name by rememberSaveable { mutableStateOf(initialName) }
    var vpas by rememberSaveable(stateSaver = UpiIdListSaver) { mutableStateOf(initialVpas) }
    var newVpa by rememberSaveable { mutableStateOf("") }
    // First Continue/Skip with nothing entered shows the warning; the second tap goes through anyway.
    // Both name and VPAs are optional — self-transfer detection works off either one alone.
    var warned by rememberSaveable { mutableStateOf(false) }
    // An ID typed in the box but never "Add"-ed counts: Continue is the natural tap, and dropping it
    // silently would leave that account's transfers counted as spending.
    val empty = name.isBlank() && vpas.isEmpty() && newVpa.isBlank()
    val proceed = { onContinue(name.trim(), withPendingUpiId(vpas, newVpa)) }
    val guarded = { if (empty && !warned) warned = true else proceed() }

    OnboardingScaffold(
        top = { StepDots(3, 4, "You") },
        footer = {
            if (warned && empty) {
                IdentityWarningCard()
                Spacer(Modifier.height(10.dp))
            }
            PrimaryButton(if (warned && empty) "Continue anyway" else "Continue", guarded)
            Spacer(Modifier.height(10.dp))
            GhostButton("Skip for now", guarded)
        },
    ) {
        Text("Which UPI IDs\nare yours?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "So moving money between your own accounts doesn't get counted as spending.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )
        Spacer(Modifier.height(22.dp))
        FieldLabel("Your name", "as it shows on UPI")
        WalletTextField(name, { name = it }, placeholder = "e.g. Bram Stoker", textStyle = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(18.dp))
        FieldLabel("Your UPI IDs")
        if (vpas.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vpas.forEach { v -> RemovableChip(v) { vpas = vpas - v } }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                WalletTextField(
                    newVpa, { newVpa = it }, placeholder = "name@bank",
                    textStyle = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.width(10.dp))
            AddButton(enabled = newVpa.isNotBlank()) {
                vpas = withPendingUpiId(vpas, newVpa)
                newVpa = ""
            }
        }
        Spacer(Modifier.height(14.dp))
        HintRow("Find these in GPay → your profile. You can add more later.")
    }
}

/** Shown once when the user tries to leave the identity step with nothing entered — the honest cost. */
@Composable
private fun IdentityWarningCard() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(WarnColor.copy(alpha = 0.08f))
            .border(1.dp, WarnColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", style = MaterialTheme.typography.labelLarge, color = WarnColor)
        Spacer(Modifier.width(10.dp))
        Text(
            "Without your name or UPI IDs, money you receive and transfers between your own accounts " +
                "can get counted as spending. You can add them anytime in Settings → You.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        )
    }
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit) {
    Text(
        "Add",
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) TextPrimary else TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Violet500.copy(alpha = 0.22f) else Color(0x0DFFFFFF))
            .border(1.dp, if (enabled) Violet500.copy(alpha = 0.5f) else HairlineColor, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 15.dp),
    )
}

// ─────────────────────────────── Done (mockup screen 7) ───────────────────────────────

@Composable
fun DoneScreen(totalPaise: Long, showBalance: Boolean, onOpen: () -> Unit) {
    WalletBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(999.dp))
                    .background(GreenCredit.copy(alpha = 0.12f))
                    .border(1.5.dp, GreenCredit.copy(alpha = 0.5f), RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center,
            ) { IconCheck(GreenCredit, size = 46.dp) }
            Spacer(Modifier.height(24.dp))
            Text("You're all set", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                "Just pay like you normally do, and your wallet updates itself.",
                style = MaterialTheme.typography.bodyLarge, color = TextSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(SurfaceColor).border(1.dp, HairlineColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp),
            ) {
                DoneItem("Capture is on")
                HorizontalDivider(color = HairlineColor)
                DoneItem(if (showBalance) "Balance set · ${Money.format(totalPaise)}" else "Showing your spending")
                HorizontalDivider(color = HairlineColor)
                DoneItem("Tracking your spends")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "A red dot appears if something needs a glance. We only notify you if payment capture " +
                    "gets switched off, or for a budget nudge you turn on.",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Reinstalling? Your backup in Downloads stays current. Restore it anytime from Settings.",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton("Open wallet", onOpen, trailingArrow = true)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DoneItem(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(999.dp))
                .background(GreenCredit.copy(alpha = 0.16f))
                .border(1.dp, GreenCredit.copy(alpha = 0.45f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) { IconCheck(GreenCredit, size = 13.dp) }
        Spacer(Modifier.width(11.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}

@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconInfo(TextTertiary, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}
