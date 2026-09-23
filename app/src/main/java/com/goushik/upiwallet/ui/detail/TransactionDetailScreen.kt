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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.Source
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
import com.goushik.upiwallet.domain.review.RemovedPayments
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
import com.goushik.upiwallet.ui.theme.glassSurface
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
        /** txn id → package of the UPI app whose screen created it, so each row names its real app. */
        val appOf: Map<String, String>,
    ) : DetailState
}

/** A Remove made on this screen, remembered so it can be undone: the status the row had just before,
 *  and whether it was made from the duplicate prompt at the top (the Undo then shows up there too). */
internal data class UndoRemove(val previous: TxnStatus, val fromDuplicatePrompt: Boolean)

/** Keeps the Undo across a rotation or process death, so a payment removed a moment ago still offers the
 *  exact Undo (back to the status it had) instead of "Put back". Saved as "<status>|<fromDuplicatePrompt>". */
internal val UndoRemoveSaver: Saver<UndoRemove?, String> = Saver(
    save = { u -> u?.let { "${it.previous.name}|${it.fromDuplicatePrompt}" } },
    restore = { saved ->
        val parts = saved.split('|')
        TxnStatus.entries.firstOrNull { it.name == parts.getOrNull(0) }
            ?.let { UndoRemove(it, parts.getOrNull(1) == "true") }
    },
)

/** The Undo to keep once the row's latest state is seen: kept while the row is still removed (an Undo tap
 *  keeps it until the restore actually lands, so the strip never flashes "Put back"), dropped once the row
 *  is live again. */
internal fun undoAfterRowSeen(undo: UndoRemove?, removed: Boolean): UndoRemove? = if (removed) undo else null

private val AmountBig = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 46.sp, fontFeatureSettings = "tnum",
)

/**
 * Transaction detail — a centered **receipt** (Phase B): big mark → name → white amount → a quiet status
 * line → a manual "Select a category" section → borderless fact rows → a quiet Remove. The screen is
 * **live**: it observes the single row, so an edit / categorizer sweep / reconciler merge reflects without
 * re-opening. Full-screen over the shared aurora; owns its own [BackHandler].
 *
 * Removing is two taps (the app's inline "Remove? Keep / Remove" strip — no dialogs) and the screen stays
 * open afterwards with an Undo, because once it closes a removed payment is out of every list. A payment
 * that is already removed shows "Put back" instead.
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
        val appOf = repo.captureApps()
        repo.observeTransactionById(txnId).collect { txn ->
            value = if (txn == null) DetailState.NotFound
            else DetailState.Loaded(txn, raws, all, ownVpas, ownNames, appOf)
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
            is DetailState.Loaded -> ReceiptBody(s)
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
private fun ReceiptBody(s: DetailState.Loaded) {
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
        StatusLine(txn, s.appOf[txn.id])
    }

    // A Remove made on this screen, so it can be undone. Set before the write (so the row never flashes
    // "Put back" as it flips to Removed), then corrected to the status the write actually replaced.
    // Saveable, so a rotation still offers the exact Undo. An Undo tap leaves it in place until the restore
    // lands and the row is seen live again (the effect below), so the way back never flashes "Put back".
    var undo by rememberSaveable(txn.id, stateSaver = UndoRemoveSaver) { mutableStateOf<UndoRemove?>(null) }
    val remove: (fromDuplicatePrompt: Boolean) -> Unit = { fromPrompt ->
        undo = UndoRemove(txn.status, fromPrompt)
        ServiceLocator.appScope.launch {
            val previous = ServiceLocator.repository.removeForUndo(txn.id)
            undo = previous?.let { UndoRemove(it, fromPrompt) }
        }
    }
    val undoRemove: () -> Unit = {
        undo?.let { u -> restorePayment(txn.id) { row -> RemovedPayments.undoStatus(row, u.previous) } }
    }

    // A duplicate the reconciler couldn't merge — surfaced here (folded in from the old Review queue).
    // Only on still-flagged, live rows, so a settled payment doesn't nag. "Remove this copy" discards
    // THIS row, and the screen stays with the Undo in that same spot (and only there).
    val removed = txn.status == TxnStatus.DISCARDED
    LaunchedEffect(removed) { undo = undoAfterRowSeen(undo, removed) }
    val undoAtTop = removed && undo?.fromDuplicatePrompt == true
    val twin = remember(txn.id, txn.status, s.all) {
        if (txn.needsReview && !removed) DuplicateDetection.twin(txn, s.all) else null
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
                "${DateTime.rowTime(twin.timestampEvent)} (${sourceLabel(twin.source, s.appOf[twin.id])}).",
            onRemove = { remove(true) },
            onKeep = { dupDismissed = true },
        )
    }
    if (undoAtTop) {
        Spacer(Modifier.height(16.dp))
        RemovedStrip(text = "Removed this copy. It no longer counts in any total.", action = "Undo", onAction = undoRemove)
    }

    Spacer(Modifier.height(8.dp))
    CategorySection(txn)
    FactsSection(txn, s.raws)
    RemoveSection(
        state = removeStateFor(removed, undo),
        onRemove = { remove(false) },
        onUndo = undoRemove,
        onPutBack = { restorePayment(txn.id) { row -> row?.let(RemovedPayments::putBackStatus) } },
    )
}

/** What the bottom of the receipt offers. */
internal enum class RemoveState { LIVE, UNDO, PUT_BACK, NONE }

/** The bottom of the receipt for a row that is [removed] or not, with the Undo this screen still holds. */
internal fun removeStateFor(removed: Boolean, undo: UndoRemove?): RemoveState = when {
    !removed -> RemoveState.LIVE
    undo?.fromDuplicatePrompt == true -> RemoveState.NONE   // its Undo is up top, where the tap was
    undo != null -> RemoveState.UNDO
    else -> RemoveState.PUT_BACK
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

/** [appPkg] = the UPI app whose screen created the row (null for SMS/manual rows, or old captures). */
@Composable
private fun StatusLine(txn: TransactionEntity, appPkg: String?) {
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
            "$word · ${sourceLabel(txn.source, appPkg)}",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
    }
}

/** The primary action on this screen: a manual category picker. */
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
                        // Only an a11y raw's package is an app — an SMS raw keeps the sender there.
                        val appPkg = raw.packageName.takeIf { raw.source == Source.A11Y }
                        Text(
                            "${sourceLabel(raw.source, appPkg)} · ${DateTime.rowTime(raw.capturedAt)}",
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

/**
 * The bottom of the receipt. A live payment: the quiet "Remove this payment" link, which opens the app's
 * inline two-step confirm (the same Keep / Remove strip as deleting an account — the app has no dialogs).
 * A removed payment: an Undo when it was removed just now on this screen, else "Put back".
 */
@Composable
private fun RemoveSection(
    state: RemoveState,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
    onPutBack: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    if (state == RemoveState.NONE) return
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp)) {
        when {
            state == RemoveState.UNDO ->
                RemovedStrip("Payment removed. It no longer counts in any total.", "Undo", onUndo)
            state == RemoveState.PUT_BACK ->
                RemovedStrip("This payment is removed, so it doesn't count in any total.", "Put back", onPutBack)
            confirming -> RemoveConfirmStrip(
                onKeep = { confirming = false },
                onRemove = { confirming = false; onRemove() },
            )
            else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row {
                    Text("Looks wrong? ", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    Text(
                        "Remove this payment",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { confirming = true },
                    )
                }
            }
        }
    }
}

/** Inline two-step confirm — mirrors Update balance's account-delete strip: what happens, then Keep / Remove. */
@Composable
private fun RemoveConfirmStrip(onKeep: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassSurface(WalletShapes.medium, blur = false).padding(14.dp)) {
        Text(
            "Remove this payment? It stops counting in your totals, your balance and the widgets. " +
                "You can undo this.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StripAction("Keep", TextSecondary, onKeep)
            Spacer(Modifier.width(6.dp))
            StripAction("Remove", RedDebit, onRemove)
        }
    }
}

/** A removed payment's note with its way back (Undo / Put back), in the same strip as the confirm. */
@Composable
private fun RemovedStrip(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glassSurface(WalletShapes.medium, blur = false)
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        StripAction(action, TextPrimary, onAction)
    }
}

@Composable
private fun StripAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge, color = color,
        modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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

/**
 * Bring a removed payment back to the status [statusFor] picks from the row as it is NOW (domain/review/
 * RemovedPayments). Process-scoped so the write survives the screen closing. The guarded restore is a
 * no-op if the row is no longer removed (e.g. a bank SMS already brought it back).
 */
private fun restorePayment(txnId: String, statusFor: (TransactionEntity?) -> TxnStatus?) {
    ServiceLocator.appScope.launch {
        val repo = ServiceLocator.repository
        val status = statusFor(repo.transactionById(txnId)) ?: return@launch
        repo.restoreRemoved(txnId, status)
    }
}
