package com.goushik.upiwallet.ui.add

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.MerchantRuleEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.categorize.Categorization
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.categorize.Categorizer
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")
private val zone: ZoneId get() = ZoneId.systemDefault()

private fun fmtDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(zone).format(DATE_FMT)

/**
 * Manual transaction entry — opened from the raised "+" in the bottom nav. Full-screen over the shared
 * aurora canvas (the same swap idiom as Update-balance / Edit-profile), so it owns its own BackHandler.
 * On Add it writes a CONFIRMED, source="manual" [TransactionEntity] (no RRN), then either records the
 * chosen category as a manual fix + learned rule (mirroring the detail screen) or, if none was picked,
 * lets the deterministic [Categorization] sweep tag it from the payee.
 */
@Composable
fun AddTransactionScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    // Account chips come from the user's anchors (HDFC / SBI …); load once.
    val accounts by produceState(initialValue = emptyList<String>()) {
        value = ServiceLocator.repository.anchors().map { it.accountLabel }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        Body(accounts, onBack)
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
        Text("Add transaction", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Body(accounts: List<String>, onBack: () -> Unit) {
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var dateMs by remember { mutableStateOf<Long?>(null) }   // null = today/now
    var account by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<Category?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))

    // ── Spent / Received ──
    DirectionToggle(direction) { direction = it }

    Spacer(Modifier.height(22.dp))
    FieldLabel("Amount")
    WalletTextField(
        amount, { amount = moneyFilter(it) }, prefix = "₹", placeholder = "0",
        keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
    )

    Spacer(Modifier.height(18.dp))
    FieldLabel(if (direction == Direction.DEBIT) "Paid to" else "Received from", "optional")
    WalletTextField(
        payee, { payee = it },
        placeholder = if (direction == Direction.DEBIT) "e.g. Swiggy" else "e.g. Rohan",
        textStyle = MaterialTheme.typography.titleLarge,
    )

    Spacer(Modifier.height(18.dp))
    FieldLabel("When")
    PickerField(dateMs?.let(::fmtDate) ?: "Today") { pickingDate = true }

    if (accounts.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        FieldLabel("Account", "optional")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.forEach { label ->
                SelectChip(label, account == label) { account = if (account == label) null else label }
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    FieldLabel("Category", "optional — we'll guess if you skip it")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Category.entries.forEach { cat ->
            SelectChip(cat.label, category == cat) { category = if (category == cat) null else cat }
        }
    }

    Spacer(Modifier.height(28.dp))
    val paise = Money.parsePaise(amount)
    val valid = paise != null && paise > 0
    PrimaryButton(
        "Add transaction",
        enabled = valid,
        onClick = {
            val amt = Money.parsePaise(amount) ?: return@PrimaryButton
            val dir = direction
            val payeeName = payee.trim().ifBlank { null }
            val bank = account
            val chosen = category
            val picked = dateMs
            // Process-scoped so the write completes even as AppShell swaps this composable out on back.
            ServiceLocator.appScope.launch {
                val repo = ServiceLocator.repository
                val now = System.currentTimeMillis()
                // Use the picked calendar day at the current time-of-day (so "today" == now).
                val tsEvent = picked?.let {
                    Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                        .atTime(LocalTime.now(zone)).atZone(zone).toInstant().toEpochMilli()
                } ?: now
                val txn = TransactionEntity(
                    id = Ids.uuid7(),
                    amountPaise = amt,
                    direction = dir,
                    status = TxnStatus.CONFIRMED,
                    payeeName = payeeName,
                    timestampEvent = tsEvent,
                    timestampCaptured = now,
                    source = Source.MANUAL,
                    needsReview = false,
                    bankLabel = bank,
                    category = chosen?.label,
                    categoryConfidence = if (chosen != null) 1.0f else null,
                    categorySource = if (chosen != null) "manual" else null,
                )
                repo.insertTransaction(txn)
                if (chosen != null) {
                    // Teach the rule, exactly like the detail-screen fix (same matchKey derivation).
                    Categorizer.matchKey(txn)?.let { key ->
                        repo.upsertMerchantRule(MerchantRuleEntity(key, chosen.label, "user", now))
                    }
                } else {
                    // No category chosen → let the deterministic pipeline tag this fresh row from its payee.
                    Categorization.run(repo)
                }
            }
            onBack()
        },
    )

    if (pickingDate) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = dateMs ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                val sel = dpState.selectedDateMillis
                TextButton(
                    enabled = sel != null,
                    onClick = { if (sel != null) dateMs = sel; pickingDate = false },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(
                state = dpState,
                title = {
                    Text(
                        "Select date",
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                },
            )
        }
    }
}

// ── pieces ──

@Composable
private fun DirectionToggle(direction: Direction, onChange: (Direction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(PillShape).background(FieldBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleHalf("Spent", direction == Direction.DEBIT, Modifier.weight(1f)) { onChange(Direction.DEBIT) }
        ToggleHalf("Received", direction == Direction.CREDIT, Modifier.weight(1f)) { onChange(Direction.CREDIT) }
    }
}

@Composable
private fun ToggleHalf(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(PillShape)
            .background(if (selected) Violet500.copy(alpha = 0.30f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) White else TextSecondary,
        )
    }
}

/** A tappable read-only field (looks like WalletTextField) that opens a picker. */
@Composable
private fun PickerField(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(WalletShapes.medium).background(FieldBg)
            .border(1.dp, HairlineColor, WalletShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(PillShape)
            .background(if (selected) Violet500.copy(alpha = 0.22f) else FieldBg)
            .border(1.dp, if (selected) Violet500 else HairlineColor, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else TextSecondary,
        )
    }
}
