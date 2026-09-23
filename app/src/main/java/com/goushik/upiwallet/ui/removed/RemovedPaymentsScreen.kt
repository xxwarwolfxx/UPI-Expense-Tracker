package com.goushik.upiwallet.ui.removed

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.review.RemovedPaymentsViewModel
import com.goushik.upiwallet.ui.review.RemovedRowUi
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Removed payments (v1.1.7, drawn to the owner's approved mockup). Every payment taken out of the totals —
 * by "Remove this payment", a Review card, or an old capture bug — listed newest first, grouped by month,
 * with one action per row: Put back. Rows a bank SMS confirmed (and nothing live already counts) carry a
 * green "Bank confirmed" tag; the first visit opens on those, since they most likely went through.
 */
@Composable
fun RemovedPaymentsScreen(
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    vm: RemovedPaymentsViewModel = viewModel(factory = RemovedPaymentsViewModel.Factory),
) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val all by ServiceLocator.repository.observeTransactions().collectAsStateWithLifecycle(emptyList())
    val removedEntities by ServiceLocator.repository.observeRemovedTransactions().collectAsStateWithLifecycle(emptyList())
    val confirmed = remember(removedEntities, all) { RemovedList.confirmedIds(removedEntities, all) }
    val when_ = remember(removedEntities) { removedEntities.associate { it.id to it.timestampEvent } }

    // First visit opens on the bank-confirmed ones (if any); after that, on everything.
    var onlyConfirmed by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.loading, confirmed.size) {
        if (onlyConfirmed == null && !state.loading) {
            val first = !seen(ctx)
            onlyConfirmed = first && confirmed.isNotEmpty()
            markSeen(ctx)
        }
    }
    var undoId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(undoId) { if (undoId != null) { delay(10_000); undoId = null } }

    val rows = state.rows.filter { onlyConfirmed != true || it.row.id in confirmed }
    val now = System.currentTimeMillis()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = topInset + 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { IconChevronLeft(TextPrimary, size = 20.dp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Removed payments", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Text(
                        "${state.rows.size} hidden from your totals",
                        style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottomInset + 96.dp),
            ) {
                item {
                    Text(
                        "Hidden from every total, widget and budget. Put one back and it counts again.",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                if (confirmed.isNotEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(WalletShapes.large)
                                .background(GreenCredit.copy(alpha = 0.09f))
                                .border(1.dp, GreenCredit.copy(alpha = 0.32f), WalletShapes.large)
                                .padding(14.dp),
                        ) {
                            Text(
                                "${confirmed.size} of these, your bank confirmed",
                                style = MaterialTheme.typography.titleSmall, color = GreenCredit,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "A bank SMS proves the money left your account, so these probably went " +
                                    "through. Many were hidden by a bug fixed in v1.1.7.",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("All · ${state.rows.size}", active = onlyConfirmed != true) { onlyConfirmed = false }
                        if (confirmed.isNotEmpty()) {
                            Chip("Bank-confirmed · ${confirmed.size}", active = onlyConfirmed == true) { onlyConfirmed = true }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (!state.loading && rows.isEmpty()) {
                    item {
                        Text(
                            "Nothing removed.",
                            style = MaterialTheme.typography.bodyLarge, color = TextTertiary,
                            modifier = Modifier.padding(top = 40.dp),
                        )
                    }
                }
                var lastMonth = ""
                rows.forEach { r ->
                    val month = RemovedList.monthLabel(when_[r.row.id] ?: now, now)
                    if (month != lastMonth) {
                        val m = month
                        item(key = "m-$m-${r.row.id}") {
                            Text(
                                m.uppercase(),
                                style = MaterialTheme.typography.labelMedium, color = TextTertiary,
                                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        lastMonth = month
                    }
                    item(key = r.row.id) {
                        RemovedRow(
                            r, bankConfirmed = r.row.id in confirmed,
                            onOpen = { onOpenTransaction(r.row.id) },
                            onPutBack = { vm.putBack(r.row.id); undoId = r.row.id },
                        )
                    }
                }
            }
        }
        undoId?.let { id ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 20.dp)
                    .fillMaxWidth()
                    .glassSurface(WalletShapes.large, strong = true)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Put back · it counts again",
                    style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Undo",
                    style = MaterialTheme.typography.labelLarge, color = White,
                    modifier = Modifier.clip(PillShape).clickable {
                        ServiceLocator.appScope.launch { ServiceLocator.repository.removeForUndo(id) }
                        undoId = null
                    }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val base = if (active) {
        Modifier.clip(PillShape).background(Violet500.copy(alpha = 0.22f)).border(1.dp, Violet500, PillShape)
    } else {
        Modifier.glassSurface(PillShape, blur = false)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) White else TextSecondary,
        modifier = base.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun RemovedRow(r: RemovedRowUi, bankConfirmed: Boolean, onOpen: () -> Unit, onPutBack: () -> Unit) {
    val ui = r.row
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ui.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ui.timeLabel, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    if (bankConfirmed) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Bank confirmed",
                            style = MaterialTheme.typography.labelSmall, color = GreenCredit,
                            modifier = Modifier.clip(WalletShapes.small)
                                .background(GreenCredit.copy(alpha = 0.08f))
                                .border(1.dp, GreenCredit.copy(alpha = 0.45f), WalletShapes.small)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                val rupees = Money.formatParts(ui.amountPaise).first
                Text(
                    if (ui.direction == Direction.CREDIT) "+$rupees" else "−$rupees",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (ui.direction == Direction.CREDIT) GreenCredit.copy(alpha = 0.8f) else TextSecondary,
                    textDecoration = TextDecoration.LineThrough,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Put back",
                    style = MaterialTheme.typography.labelMedium, color = White,
                    modifier = Modifier.clip(PillShape)
                        .border(1.dp, White.copy(alpha = 0.25f), PillShape)
                        .background(White.copy(alpha = 0.06f))
                        .clickable(onClick = onPutBack)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(HairlineColor))
    }
}

private const val PREFS = "ui_prefs"
private const val KEY_SEEN = "removed_payments_seen"

private fun seen(ctx: Context): Boolean =
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

private fun markSeen(ctx: Context) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}
