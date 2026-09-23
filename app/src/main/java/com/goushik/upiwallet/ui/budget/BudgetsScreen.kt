package com.goushik.upiwallet.ui.budget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.budget.BUDGET_PERIODS
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.domain.budget.budgetStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.TogglePill
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.Amber500
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.Coral500
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.launch

/**
 * Budgets (Phase 2) — three optional caps (today / this week / this month), each with a live progress bar
 * (calm aurora → amber at 80% → coral when over) and an inline amount editor. The "Nudge me" toggle opts
 * into the budget nudge (asking for POST_NOTIFICATIONS on Android 13+ only if it isn't granted yet — Home
 * already asks once, for the capture-off reminder). Full-screen over the
 * shared aurora; owns its own [BackHandler]. Reads the repo flows directly (like SettingsScreen), so the
 * bars update the instant a payment lands.
 */
@Composable
fun BudgetsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val repo = ServiceLocator.repository
    val budgets by repo.observeBudgets().collectAsStateWithLifecycle(emptyList())
    val txns by repo.observeTransactions().collectAsStateWithLifecycle(emptyList())
    val profile by repo.observeProfile().collectAsStateWithLifecycle(null)
    val alertsOn by ServiceLocator.uiPrefs.budgetAlerts.collectAsStateWithLifecycle()

    val ownVpas = profile?.ownVpaSet() ?: emptySet()
    val ownNames = profile?.ownNameSet() ?: emptySet()
    val now = System.currentTimeMillis()
    val byPeriod = remember(budgets) { budgets.associateBy { it.period } }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "A limit for the day, week, or month. We'll nudge you at 80%.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
        Spacer(Modifier.height(20.dp))

        BUDGET_PERIODS.forEach { period ->
            val budget = byPeriod[period.name]?.takeIf { it.limitPaise > 0L }
            val status = budget?.let { budgetStatus(it, txns, ownVpas, ownNames, now) }
            BudgetCard(
                period = period,
                status = status,
                onSave = { paise ->
                    ServiceLocator.appScope.launch {
                        repo.upsertBudget(BudgetEntity(period.name, paise, System.currentTimeMillis()))
                    }
                },
                onClear = { ServiceLocator.appScope.launch { repo.deleteBudget(period.name) } },
            )
            Spacer(Modifier.height(13.dp))
        }

        Spacer(Modifier.height(5.dp))
        AlertsToggle(on = alertsOn)
        Spacer(Modifier.height(12.dp))
        Text(
            "Off by default. The nudge shows a percentage only, never an amount.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary,
            modifier = Modifier.padding(start = 2.dp),
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            IconChevronLeft(TextPrimary, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text("Budgets", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
    }
}

@Composable
private fun BudgetCard(
    period: InsightsPeriod,
    status: BudgetStatus?,
    onSave: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var editing by remember(period) { mutableStateOf(false) }
    var draft by remember(period) { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().glassSurface(WalletShapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(periodLabel(period), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            if (status != null && !editing) {
                Text(Money.format(status.limitPaise), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.width(10.dp))
                EditPill("Edit") { draft = rupeesOf(status.limitPaise); editing = true }
            } else if (status == null && !editing) {
                EditPill("Set a limit") { draft = ""; editing = true }
            }
        }

        if (status != null && !editing) {
            val frac = (status.pct / 100f).coerceIn(0f, 1f)
            val fill: Brush = when {
                status.isOver -> SolidColor(Coral500)
                status.isNear -> SolidColor(Amber500)
                else -> AuroraBrush
            }
            Box(Modifier.fillMaxWidth().height(11.dp).clip(PillShape).background(White.copy(alpha = 0.10f))) {
                Box(Modifier.fillMaxWidth(frac).height(11.dp).clip(PillShape).background(fill))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pctColor = when {
                    status.isOver -> Coral500
                    status.isNear -> Amber500
                    else -> TextSecondary
                }
                Text(
                    "${status.pct}% · ${Money.format(status.spentPaise)} spent",
                    style = MaterialTheme.typography.bodySmall, color = pctColor,
                )
                Spacer(Modifier.weight(1f))
                val rightText = when {
                    status.remainingPaise < 0L -> "Over by ${Money.format(-status.remainingPaise)}"
                    status.remainingPaise == 0L -> "Limit reached"
                    else -> "${Money.format(status.remainingPaise)} left"
                }
                Text(rightText, style = MaterialTheme.typography.bodySmall, color = if (status.isOver) Coral500 else TextTertiary)
            }
        }

        if (editing) {
            WalletTextField(
                value = draft,
                onValueChange = { draft = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                prefix = "₹", placeholder = "0",
                keyboardType = KeyboardType.Decimal,
                textStyle = MaterialTheme.typography.headlineMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val paise = Money.parsePaise(draft)
                val valid = paise != null && paise > 0L
                ActionPill("Save", accent = true, enabled = valid) {
                    if (valid) { onSave(paise!!); editing = false }
                }
                ActionPill("Cancel") { editing = false }
                if (status != null) {
                    Spacer(Modifier.weight(1f))
                    ActionPill("Remove") { onClear(); editing = false }
                }
            }
        }
    }
}

@Composable
private fun EditPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape).background(White.copy(alpha = 0.08f))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}

@Composable
private fun ActionPill(label: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape)
            .background(if (accent) White.copy(alpha = if (enabled) 0.14f else 0.06f) else White.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = if (accent && enabled) White else TextSecondary,
        )
    }
}

@Composable
private fun AlertsToggle(on: Boolean) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* check() self-guards */ }
    Row(
        Modifier.fillMaxWidth().glassSurface(WalletShapes.large).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Nudge me at 80%", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "One notification when you near or cross a limit",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary,
            )
        }
        Spacer(Modifier.width(16.dp))
        TogglePill(on = on) { want ->
            ServiceLocator.uiPrefs.setBudgetAlerts(want)
            if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (!granted) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private fun periodLabel(period: InsightsPeriod): String = when (period) {
    InsightsPeriod.DAY -> "Today"
    InsightsPeriod.WEEK -> "This week"
    InsightsPeriod.MONTH -> "This month"
    else -> period.name
}

/** Rupee text (no ₹, thousands-separated) for pre-filling the editor — matches the money field's filter. */
private fun rupeesOf(paise: Long): String {
    val rupees = "%,d".format(paise / 100)
    val frac = paise % 100
    return if (frac == 0L) rupees else rupees + ".%02d".format(frac)
}
