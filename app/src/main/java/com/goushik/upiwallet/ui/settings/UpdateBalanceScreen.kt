package com.goushik.upiwallet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.GhostButton
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.IconInfo
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.launch

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }

/** Pre-fill string for a money field: rupees with thousands separators, + ".dd" only if non-zero paise.
 *  No ₹ symbol — the field's prefix carries it (mirrors what moneyFilter accepts). */
private fun prefillOf(paise: Long): String {
    val rupees = "%,d".format(paise / 100)
    val frac = paise % 100
    return if (frac == 0L) rupees else rupees + ".%02d".format(frac)
}

private sealed interface AnchorsState {
    data object Loading : AnchorsState
    data class Loaded(val anchors: List<BalanceAnchorEntity>) : AnchorsState
}

private class FieldState(val label: String, initial: String) {
    var text by mutableStateOf(initial)
}

/**
 * The wallet's "truth button" — re-anchor every account (USER-FLOW Part C). Full-screen over the
 * shared aurora canvas. The user opens their bank app, reads the real number, and types it in; on
 * Update we clear + re-write all anchors with a fresh timestamp, so BalanceCalculator counts only
 * spends after this moment and `available` snaps to the entered total.
 */
@Composable
fun UpdateBalanceScreen(onBack: () -> Unit, onAddAccount: () -> Unit) {
    BackHandler { onBack() }

    val state by produceState<AnchorsState>(AnchorsState.Loading) {
        value = AnchorsState.Loaded(ServiceLocator.repository.anchors())
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        when (val s = state) {
            AnchorsState.Loading -> CenterNote("Loading…")
            is AnchorsState.Loaded -> Body(s.anchors, onBack, onAddAccount)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            IconChevronLeft(TextPrimary, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text("Update balance", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@Composable
private fun Body(anchors: List<BalanceAnchorEntity>, onBack: () -> Unit, onAddAccount: () -> Unit) {
    // Field state seeded from the loaded anchors (label + pre-filled rupee text); user edits live here.
    // Keyed on `anchors` so it re-seeds if they change; synchronous so the form never renders blank.
    val fields = remember(anchors) {
        mutableStateListOf<FieldState>().apply {
            anchors.forEach { add(FieldState(it.accountLabel, prefillOf(it.baselinePaise))) }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("What's the real balance?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
    Spacer(Modifier.height(10.dp))
    Text(
        "Open your bank app, read the actual balance, and type it in. The wallet snaps to it and tracks " +
            "every payment from here.",
        style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
    )
    Spacer(Modifier.height(24.dp))

    if (anchors.isEmpty()) {
        // No anchors yet (spend-only onboarding, or a skipped balance step) — re-anchoring needs an
        // account to write to, so route the user to "Add account" to bootstrap their first one.
        HintRow("No accounts yet — add your first below.")
        Spacer(Modifier.height(20.dp))
        GhostButton("+ Add account", onAddAccount)
        return
    }

    fields.forEachIndexed { i, f ->
        if (i > 0) Spacer(Modifier.height(18.dp))
        FieldLabel(f.label)
        WalletTextField(
            f.text, { f.text = moneyFilter(it) }, prefix = "₹", placeholder = "0",
            keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
        )
    }

    Spacer(Modifier.height(14.dp))
    HintRow("We never connect to your bank — this is just you telling us the truth.")

    Spacer(Modifier.height(24.dp))
    // Add a NEW account (additive, its own screen) — sits directly above the re-anchor "Update" button.
    GhostButton("+ Add account", onAddAccount)
    Spacer(Modifier.height(12.dp))
    val parsed = fields.map { Money.parsePaise(it.text) }
    val valid = fields.isNotEmpty() && parsed.all { it != null && it >= 0 }
    PrimaryButton(
        "Update",
        onClick = {
            // Process-scoped so the write completes even as AppShell swaps this composable out on back.
            ServiceLocator.appScope.launch {
                val repo = ServiceLocator.repository
                val now = System.currentTimeMillis()
                // One atomic transaction so observers (Home / Settings) never see an empty or partial
                // anchor set mid-update — otherwise Available would briefly flicker toward ₹0.
                repo.inTransaction {
                    repo.clearAnchors()
                    fields.forEach { f ->
                        repo.upsertAnchor(BalanceAnchorEntity(Ids.uuid7(), f.label, Money.parsePaise(f.text)!!, now))
                    }
                }
            }
            onBack()
        },
        enabled = valid,
    )
}

@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconInfo(TextTertiary, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
    }
}
