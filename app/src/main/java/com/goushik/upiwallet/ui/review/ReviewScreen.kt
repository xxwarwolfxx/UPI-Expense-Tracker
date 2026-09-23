package com.goushik.upiwallet.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.RedDebit
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.home.RecentSkeleton
import com.goushik.upiwallet.ui.home.TransactionListCard
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.util.Money

/**
 * The Review tab — the payments we weren't sure about. "Did this go through?" cards come first (a payment
 * screen the owner likely backed out of — still counted until he says Remove), then the **list** of
 * `needsReview` rows, where tapping a row opens the full detail to pick a category (clearing the flag).
 * The cards reuse the duplicate notice's prompt style from the detail screen (plain glass, hairline, one
 * amber dot, a pill + a text action), so Review gets no new visual language. AppShell draws the aurora +
 * hosts the nav, so this is just a LazyColumn.
 */
@Composable
fun ReviewScreen(
    onOpenTransaction: (String) -> Unit,
    onFixUpiIds: () -> Unit = {},
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewContent(
        state = state,
        onOpenTransaction = onOpenTransaction,
        onKeep = vm::keep,
        onRemove = vm::remove,
        onUndo = vm::undoRemove,
        onRemoveSamples = vm::removeSamplePayments,
        onFixUpiIds = onFixUpiIds,
    )
}

@Composable
fun ReviewContent(
    state: ReviewUiState,
    onOpenTransaction: (String) -> Unit = {},
    onKeep: (String) -> Unit = {},
    onRemove: (BackedOutCard) -> Unit = {},
    onUndo: () -> Unit = {},
    onRemoveSamples: () -> Unit = {},
    onFixUpiIds: () -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = topInset + 8.dp,
            bottom = BottomNavHeight + bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ReviewHeader(state) }

        state.undo?.let { u -> item(key = "undo") { UndoStrip(u, onUndo) } }

        // The two sample-data cards sit at the top, each only while it applies.
        if (!state.loading && state.samples.count > 0) {
            item(key = "samples") { SamplePaymentsCard(state.samples, onRemoveSamples, onOpenTransaction) }
        }
        if (!state.loading && state.samples.sampleUpiIds.isNotEmpty()) {
            item(key = "sample-ids") { DemoUpiIdsCard(state.samples, onFixUpiIds) }
        }

        when {
            state.loading -> item { RecentSkeleton() }
            state.count == 0 -> if (state.undo == null && !state.samples.needsAttention) item { AllCaughtUp() }
            else -> {
                items(state.backedOut, key = { it.id }) { card ->
                    BackedOutPrompt(
                        card,
                        onOpen = { onOpenTransaction(card.id) },
                        onRemove = { onRemove(card) },
                        onKeep = { onKeep(card.id) },
                    )
                }
                if (state.items.isNotEmpty()) item { TransactionListCard(state.items, onOpenTransaction) }
            }
        }
    }
}

@Composable
private fun ReviewHeader(state: ReviewUiState) {
    val count = state.count
    val needs = if (count == 1) "payment needs" else "payments need"
    Column {
        Text("Review", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                count == 0 -> "Payments we weren't sure about land here."
                state.backedOut.isEmpty() -> "$count $needs a quick look — tap one to set a category."
                else -> "$count $needs a quick look."
            },
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
    }
}

/** "Did this go through?" — the duplicate notice's shape: amber dot + sentence, then Remove (pill) / Keep.
 *  Tapping the sentence opens the payment, to check it before answering. */
@Composable
private fun BackedOutPrompt(card: BackedOutCard, onOpen: () -> Unit, onRemove: () -> Unit, onKeep: () -> Unit) {
    PromptCard {
        Row(
            Modifier.clip(WalletShapes.small).clickable(onClick = onOpen),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 5.dp).size(6.dp).clip(PillShape).background(WarnColor))
            Spacer(Modifier.width(9.dp))
            Text(
                "Did this go through? ${card.body}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = TextSecondary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(PillShape)
                    .background(White.copy(alpha = 0.10f))
                    .border(1.dp, White.copy(alpha = 0.22f), PillShape)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Remove", style = MaterialTheme.typography.labelMedium, color = White)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "Keep", style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onKeep)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/** The Undo offered right after a Remove from this tab — the same card, no dot. */
@Composable
private fun UndoStrip(u: ReviewUndo, onUndo: () -> Unit) {
    PromptCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Removed ${Money.format(u.amountPaise)} to ${u.who}. It no longer counts in any total.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Undo", style = MaterialTheme.typography.labelMedium, color = White,
                modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onUndo)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * "Sample payments found": the developer seeder's rows still in the owner's totals. The three newest are
 * listed; "See the list" opens the rest in place. "Remove all" asks once, inline (the app has no dialogs),
 * then moves every row to Settings → Removed payments, where each can be put back.
 */
@Composable
private fun SamplePaymentsCard(s: SampleCleanup, onRemoveAll: () -> Unit, onOpen: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    WarnCard {
        Text(s.rowsTitle, style = CardTitle, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(sampleBody(s), style = CardBody, color = TextSecondary)

        Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
            (if (expanded) s.rows else s.rows.take(MINI_ROWS)).forEach { r -> SampleMiniRow(r) { onOpen(r.id) } }
        }

        Spacer(Modifier.height(12.dp))
        if (confirming) {
            Text(
                "Remove ${s.count} sample ${if (s.count == 1) "payment" else "payments"}? They stop counting in " +
                    "your totals and move to Removed payments in Settings, where you can put any back.",
                style = CardBody, color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction("Keep", TextSecondary) { confirming = false }
                Spacer(Modifier.width(6.dp))
                TextAction("Remove all", RedDebit) { confirming = false; onRemoveAll() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Remove all ${s.count}", PillStyle.PRIMARY) { confirming = true }
                if (s.count > MINI_ROWS) {
                    Spacer(Modifier.width(8.dp))
                    PillButton(if (expanded) "Show fewer" else "See the list", PillStyle.GHOST) { expanded = !expanded }
                }
            }
        }
    }
}

/** "Your UPI IDs look like the demo ones" — the profile still holds the seeder's sample IDs. */
@Composable
private fun DemoUpiIdsCard(s: SampleCleanup, onFix: () -> Unit) {
    WarnCard {
        Text("Your UPI IDs look like the demo ones", style = CardTitle, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(s.upiIdsBody, style = CardBody, color = TextSecondary)
        Spacer(Modifier.height(12.dp))
        PillButton("Fix my UPI IDs", PillStyle.PLAIN, onFix)
    }
}

/** "They add ₹29,708 to your spending and ₹6,249 of made-up income (21 Apr – 3 Jun). None of them are
 *  yours." — the amounts in white. Whole rupees, rounded, like the mockup. */
private fun sampleBody(s: SampleCleanup) = buildAnnotatedString {
    val bold = SpanStyle(color = White, fontWeight = FontWeight.SemiBold)
    append(if (s.count == 1) "It adds " else "They add ")
    val spend = s.liveDebitPaise > 0
    val income = s.liveCreditPaise > 0
    if (spend) {
        withStyle(bold) { append(wholeRupees(s.liveDebitPaise)) }
        append(" to your spending")
    }
    if (spend && income) append(" and ")
    if (income) {
        withStyle(bold) { append(wholeRupees(s.liveCreditPaise)) }
        append(" of made-up income")
    }
    if (!spend && !income) append("nothing to your totals")
    if (s.rangeLabel.isNotEmpty()) append(" (${s.rangeLabel})")
    append(if (s.count == 1) ". It isn't yours." else ". None of them are yours.")
}

private fun wholeRupees(paise: Long): String = "₹%,d".format((paise + 50) / 100)

@Composable
private fun SampleMiniRow(r: SampleRowUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                r.title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${r.dayLabel} · sample",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = TextTertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        val rupees = Money.formatParts(r.amountPaise).first
        Text(
            if (r.direction == Direction.CREDIT) "+$rupees" else "−$rupees",
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
            color = if (r.direction == Direction.CREDIT) GreenCredit.copy(alpha = 0.8f) else TextSecondary,
        )
    }
}

/** The mockup's amber card: amber hairline over a faint amber wash. */
@Composable
private fun WarnCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape)
            .background(WarnColor.copy(alpha = 0.07f))
            .border(1.dp, WarnColor.copy(alpha = 0.45f), CardShape)
            .padding(14.dp),
        content = content,
    )
}

private enum class PillStyle { PRIMARY, GHOST, PLAIN }

/** The mockup's pill buttons: PRIMARY fills with the aurora, GHOST is a quiet outline, PLAIN a white one. */
@Composable
private fun PillButton(label: String, style: PillStyle, onClick: () -> Unit) {
    val base = Modifier.clip(PillShape)
    val shaped = when (style) {
        PillStyle.PRIMARY -> base.background(AuroraBrush)
        else -> base.background(White.copy(alpha = 0.06f)).border(1.dp, White.copy(alpha = 0.22f), PillShape)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        color = if (style == PillStyle.GHOST) TextSecondary else White,
        modifier = shaped.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = color,
        modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private const val MINI_ROWS = 3
private val CardShape = RoundedCornerShape(18.dp)
private val CardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
private val CardBody = TextStyle(fontSize = 12.5.sp, lineHeight = 18.75.sp)

/** Plain glass + hairline — the prompt container the detail screen's duplicate notice uses. */
@Composable
private fun PromptCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(WalletShapes.large)
            .background(White.copy(alpha = 0.05f))
            .border(1.dp, HairlineColor, WalletShapes.large)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun AllCaughtUp() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(PillShape).background(GreenCredit.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            IconCheck(GreenCredit, size = 36.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("All caught up", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Nothing needs review right now.",
            style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
        )
    }
}

// ── Preview (sample data; the real screen binds to ReviewViewModel) ──
private val SampleRows = listOf(
    TxnRowUi("1", "CRED Club", 1_234_500, Direction.DEBIT, false, true, "2:40 PM", category = null, isPerson = false),
    TxnRowUi("2", "Navi Finserv", 420_000, Direction.DEBIT, false, true, "Yesterday", category = null, isPerson = false),
    TxnRowUi("3", "Juniper Quill T", 45_678, Direction.DEBIT, false, true, "Mon", category = null, isPerson = true),
)

private val SampleBackedOut = listOf(
    BackedOutCard("4", 51_000, "Ramesh Kumar", "9 Jun · 10:25 PM", "Google Pay", gapSeconds = 7),
)

@Preview(heightDp = 880)
@Composable
private fun ReviewListPreview() {
    UPIWalletTheme {
        WalletBackground {
            ReviewContent(ReviewUiState(loading = false, items = SampleRows, backedOut = SampleBackedOut))
        }
    }
}

@Preview(heightDp = 880)
@Composable
private fun ReviewEmptyPreview() {
    UPIWalletTheme {
        WalletBackground {
            ReviewContent(ReviewUiState(loading = false))
        }
    }
}
