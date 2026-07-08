package com.goushik.upiwallet.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.Payee
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.categorize.CategoryActions
import com.goushik.upiwallet.domain.insights.Cities
import com.goushik.upiwallet.domain.insights.CityMapLoader
import com.goushik.upiwallet.domain.insights.nearestLabel
import com.goushik.upiwallet.domain.review.DuplicateDetection
import com.goushik.upiwallet.ui.common.IconBusiness
import com.goushik.upiwallet.ui.common.IconChevronDown
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.IconPerson
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.SpaceGrotesk
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.accountLabel
import com.goushik.upiwallet.util.prettyName
import com.goushik.upiwallet.util.signedRupees
import com.goushik.upiwallet.util.sourceLabel
import com.goushik.upiwallet.util.typeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Loaded(
        val txn: TransactionEntity,
        val raws: List<RawEventEntity>,
        val all: List<TransactionEntity>,
        val ownVpas: Set<String>,
        val ownNames: Set<String>,
    ) : DetailState
}

private val AmountBig = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 46.sp, fontFeatureSettings = "tnum",
)

/**
 * Transaction detail — a centered **receipt** (Phase B): big mark → name → white amount → a quiet status
 * line → a manual "Select a category" section (NO AI on this screen) → borderless fact rows → a quiet
 * Remove. The screen is **live**: it observes the single row, so an edit / AI sweep / reconciler merge
 * reflects without re-opening. Full-screen over the shared aurora; owns its own [BackHandler].
 */
@Composable
fun TransactionDetailScreen(txnId: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val state by produceState<DetailState>(DetailState.Loading, txnId) {
        val repo = ServiceLocator.repository
        val raws = repo.rawEventsFor(txnId)
        val profile = repo.profile()
        val ownVpas = profile?.ownVpaSet() ?: emptySet()
        val ownNames = profile?.ownNameSet() ?: emptySet()
        // One-shot snapshot for the duplicate-twin glance (a human backstop — staleness is harmless).
        val all = repo.transactions()
        repo.observeTransactionById(txnId).collect { txn ->
            value = if (txn == null) DetailState.NotFound
            else DetailState.Loaded(txn, raws, all, ownVpas, ownNames)
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        BackBar(onBack)
        when (val s = state) {
            DetailState.Loading -> CenterNote("Loading…")
            DetailState.NotFound -> CenterNote("Transaction not found")
            is DetailState.Loaded -> ReceiptBody(s, onBack)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun BackBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Bare chevron — no bordered glass chip (same minimal family as the dead bell).
        Box(
            Modifier.size(40.dp).clip(WalletShapes.medium).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            IconChevronLeft(TextPrimary, size = 24.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiptBody(s: DetailState.Loaded, onBack: () -> Unit) {
    val txn = s.txn
    val isSelf = BalanceCalculator.isSelfTransfer(txn, s.ownVpas, s.ownNames)
    val isPerson = Payee.isPerson(txn.payeeName, txn.payeeVpa) && !isSelf

    // ── receipt head (centered) ──
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            val glyph = White.copy(alpha = 0.92f)
            if (isPerson) IconPerson(glyph, Modifier, 38.dp) else IconBusiness(glyph, Modifier, 38.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            prettyName(txn.payeeName) ?: txn.payeeVpa ?: txn.bankLabel ?: "Payment",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(txn.payeeVpa, typeLabel(txn, isSelf)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = TextTertiary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AmountLine(txn, isSelf)
        Spacer(Modifier.height(12.dp))
        StatusLine(txn)
    }

    // A duplicate the reconciler couldn't merge — surfaced here (folded in from the old Review queue).
    // Only on still-flagged rows, so a settled payment doesn't nag. "Remove this copy" discards THIS row.
    val twin = remember(txn.id, s.all) {
        if (txn.needsReview) DuplicateDetection.twin(txn, s.all) else null
    }
    var dupDismissed by remember(txn.id) { mutableStateOf(false) }
    if (twin != null && !dupDismissed) {
        val twinAmount = signedRupees(
            twin.amountPaise, twin.direction,
            BalanceCalculator.isSelfTransfer(twin, s.ownVpas, s.ownNames),
        )
        val who = prettyName(txn.payeeName) ?: txn.payeeVpa ?: "this payment"
        Spacer(Modifier.height(16.dp))
        DuplicateNotice(
            sentence = "Another $twinAmount to $who was captured at " +
                "${DateTime.rowTime(twin.timestampEvent)} (${sourceLabel(twin.source)}).",
            onRemove = { removePayment(txn.id); onBack() },
            onKeep = { dupDismissed = true },
        )
    }

    Spacer(Modifier.height(8.dp))
    CategorySection(txn)
    FactsSection(txn, s.raws)
    RemoveLine(onRemove = { removePayment(txn.id); onBack() })
}

/** The duplicate prompt — plain glass + hairline; the only warn accent is the tiny amber dot. Ported from
 *  the old Review focus queue. "Remove this copy" discards the row being viewed; "Keep both" dismisses. */
@Composable
private fun DuplicateNotice(sentence: String, onRemove: () -> Unit, onKeep: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(WalletShapes.large)
            .background(White.copy(alpha = 0.05f))
            .border(1.dp, HairlineColor, WalletShapes.large)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(6.dp).clip(PillShape).background(WarnColor))
            Spacer(Modifier.width(9.dp))
            Text(
                "Looks like a duplicate. $sentence",
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
                Text("Remove this copy", style = MaterialTheme.typography.labelMedium, color = White)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "Keep both", style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onKeep)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AmountLine(txn: TransactionEntity, isSelf: Boolean) {
    val (rupees, frac) = Money.formatParts(txn.amountPaise)
    val sign = when {
        isSelf -> ""
        txn.direction == Direction.CREDIT -> "+"
        else -> "−"
    }
    // White amount (the hero convention) — the old red blended into the dark/magenta background.
    val color = when {
        isSelf -> TextSecondary
        txn.direction == Direction.CREDIT -> GreenCredit
        else -> White
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$sign$rupees", style = AmountBig, color = color)
        Text(
            frac,
            style = AmountBig.copy(fontSize = 25.sp), color = color.copy(alpha = 0.55f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun StatusLine(txn: TransactionEntity) {
    val (word, color) = when (txn.status) {
        TxnStatus.CONFIRMED -> "Confirmed" to GreenCredit
        TxnStatus.PENDING -> "Pending" to WarnColor
        TxnStatus.UNCONFIRMED -> "Unconfirmed" to TextTertiary
        TxnStatus.DISCARDED -> "Removed" to RedDebit
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(PillShape).background(color))
        Spacer(Modifier.width(8.dp))
        // "Captured via" is merged in here — no separate chip.
        Text(
            "$word · ${sourceLabel(txn.source)}",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
    }
}

/** The primary action on this screen: a MANUAL category picker — a user override of the auto-categorization. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(txn: TransactionEntity) {
    var query by remember(txn.id) { mutableStateOf("") }
    val choices = remember(query) {
        Category.entries.filter {
            it != Category.SELF_TRANSFER && it.label.contains(query.trim(), ignoreCase = true)
        }
    }
    SectionDivider()
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Select a category",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp), color = TextPrimary,
            )
            if (txn.needsReview) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(6.dp).clip(PillShape).background(WarnColor))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "Tap one below, or search to narrow them down.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary,
        )
        Spacer(Modifier.height(13.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { cat ->
                CategoryChip(label = cat.label, selected = cat.label == txn.category) {
                    applyCategory(txn.id, cat.label)
                }
            }
        }
        if (choices.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "No category matches “${query.trim()}”. Custom categories are coming soon.",
                style = MaterialTheme.typography.bodySmall, color = TextTertiary,
            )
        }
        Spacer(Modifier.height(12.dp))
        WalletTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search a category…",
            textStyle = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape)
            .background(if (selected) White.copy(alpha = 0.12f) else FieldBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun FactsSection(txn: TransactionEntity, raws: List<RawEventEntity>) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        FactRow("When", DateTime.full(txn.timestampEvent))
        LocationRow(txn)
        FactRow("Account", accountLabel(txn))
        FactRow("Reference", txn.rrn ?: "—")
        OriginalMessageRow(raws)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    SectionDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * "Location · <neighbourhood>" — just the place text (red-pen: no expand-to-map). An OFFLINE
 * nearest-locality lookup over the bundled OSM basemap (no network, no reverse-geocoding), loading labels
 * only — cheap. Shown only when a rounded ~110 m fix was captured (opt-in location); honest fallbacks:
 * no fix → "Not captured"; outside the 3 mapped cities → "area not mapped".
 */
@Composable
private fun LocationRow(txn: TransactionEntity) {
    val lat = txn.latRounded
    val lng = txn.lngRounded
    if (lat == null || lng == null) {
        FactRow("Location", "Not captured")
        return
    }
    val city = remember(lat, lng) { Cities.cityFor(lat, lng) }
    if (city == null) {
        FactRow("Location", "Captured · area not mapped")
        return
    }
    val ctx = LocalContext.current
    val placeName by produceState(city.displayName, city.slug, lat, lng) {
        val name = withContext(Dispatchers.IO) {
            CityMapLoader.loadLabels(ctx, city.slug)?.let { nearestLabel(it, lat, lng) }
        }
        if (name != null) value = name
    }
    FactRow("Location", placeName)
}

/** "Original message → N captures ›" — a borderless fact row that expands the raw payloads. */
@Composable
private fun OriginalMessageRow(raws: List<RawEventEntity>) {
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "msg")
    SectionDivider()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Original message", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(
                "${raws.size} ${if (raws.size == 1) "capture" else "captures"}",
                style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
            )
            Spacer(Modifier.width(6.dp))
            IconChevronDown(TextTertiary, size = 18.dp, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(visible = open) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp)) {
                if (raws.isEmpty()) {
                    Text(
                        "No raw capture stored for this transaction.",
                        style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    )
                } else {
                    raws.forEachIndexed { i, raw ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        Text(
                            "${sourceLabel(raw.source)} · ${DateTime.rowTime(raw.capturedAt)}",
                            style = MaterialTheme.typography.labelSmall, color = TextTertiary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(raw.payload, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveLine(onRemove: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        Row {
            Text("Looks wrong? ", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            Text(
                "Remove this payment",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onRemove),
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
    }
}

/** Manual category fix + learned rule — the shared dual-write (matchKey matches the categorizer). The live
 *  Flow re-emits with the new category + cleared review flag, so the UI updates without local override. */
private fun applyCategory(txnId: String, label: String) {
    ServiceLocator.appScope.launch {
        val repo = ServiceLocator.repository
        val txn = repo.transactionById(txnId) ?: return@launch
        CategoryActions.setManual(repo, txn, label)
    }
}

private fun removePayment(txnId: String) {
    ServiceLocator.appScope.launch { ServiceLocator.repository.discard(txnId) }
}
