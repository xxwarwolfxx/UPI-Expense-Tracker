package com.goushik.upiwallet.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.ui.common.IconChevronDown
import com.goushik.upiwallet.ui.common.IconBusiness
import com.goushik.upiwallet.ui.common.IconFlip
import com.goushik.upiwallet.ui.common.IconPerson
import com.goushik.upiwallet.ui.theme.Amber500
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.Coral500
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.Indigo1000
import com.goushik.upiwallet.ui.theme.Lavender300
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.SpaceGrotesk
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.ui.theme.heroCardBackground
import com.goushik.upiwallet.ui.theme.rememberReduceMotion
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import kotlin.math.sin
import kotlinx.coroutines.launch

// Space Grotesk, tabular — for money in chips / stat tiles / rows where no Material slot fits the size.
private val MoneyRow = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp, fontFeatureSettings = "tnum",
)

/** Just the greeting + name now — the avatar mark (and the long-gone bell) are removed; nothing competes
 *  with the hero for the top of the screen. */
@Composable
fun TopBar(name: String) {
    Column {
        Text(DateTime.greeting(), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            name.ifBlank { "there" },
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )
    }
}

/** The spend↔balance half-turn card flip. */
private const val FlipMs = 650

/**
 * The hero card — a physical card floating above the screen: gyroscope 3D tilt + a moving aurora shine
 * (DeviceTilt.kt), a soft drop shadow lifting it onto its own plane, and (spend-only mode, Phase C) a
 * 180° flip when switching between the spend and balance faces. The CONTENT forks on the wallet mode:
 * balance mode shows the available-balance wallet; spend-only shows "Spent this month" with a flip to peek
 * the balance (re-masking on screen-off).
 *
 * The face + chips-dropdown state lives HERE (not in the face composables) so the flip can spin the whole
 * card and the dropdown survives a flip (red-pen: flipping must not collapse it).
 *
 * [fancy] (Settings → Wallet → "Fancy card") gates ALL the theatrics: off = a plain frosted-glass card —
 * no tilt (the sensor is never even registered), no shine/glow, no shadow, no emboss, and the face switch
 * is an instant swap instead of the 180° flip.
 */
@Composable
fun HeroBalanceCard(
    fancy: Boolean,
    showBalance: Boolean,
    availablePaise: Long,
    accounts: List<AccountBalance>,
    accountMonthSpend: List<AccountSpend>,
    monthSpentPaise: Long,
    monthCount: Int,
    onUpdateBalance: () -> Unit,
    monthBudget: BudgetStatus? = null,
    onOpenBudgets: () -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    // Spend-mode face: false = spend (the default — plain remember, so process death resets it: the privacy
    // posture), true = balance. `pendingFace` holds the flip's destination while the card is mid-spin.
    var face by remember { mutableStateOf(false) }
    var pendingFace by remember { mutableStateOf<Boolean?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val flip = remember { Animatable(0f) }
    // The face actually printed on the card swaps the instant the spinning card is edge-on (90°) — the
    // viewer never sees the content change. derivedStateOf → recomposes once per crossing, not per frame.
    val pastSwap by remember { derivedStateOf { flip.value >= 90f } }
    val shownFace = if (pastSwap) (pendingFace ?: face) else face

    fun flipTo(target: Boolean) {
        if (flip.isRunning || target == face) return
        if (reduceMotion || !fancy) { face = target; return }
        pendingFace = target
        scope.launch {
            try {
                flip.animateTo(180f, tween(FlipMs, easing = FastOutSlowInEasing))
            } finally {
                // At 180° the whole face is counter-rotated 180°, so 180+180 ≡ 0+0 — the snap is invisible.
                face = target
                flip.snapTo(0f)
                pendingFace = null
            }
        }
    }

    // Privacy: default-to-spend — screen-off silently resets to the spend face (no spin; the screen is off).
    // The dropdown deliberately survives: the spend face's chips are per-account spends, not balances.
    if (!showBalance && accounts.isNotEmpty()) {
        val ctx = LocalContext.current
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { pendingFace = null; face = false }
            }
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }
    }

    val surface = if (fancy) {
        // Gyroscope-driven "physical card": tilt3d slants the whole card with the phone; specularShine
        // sweeps a moving aurora shine across it. Both read `tilt` only inside their draw/layer lambdas →
        // no recomposition. The sensor hook lives in this branch so fancy-off never registers a listener.
        val tilt = rememberDeviceTilt()
        Modifier
            .cardFlip(flip)
            .tilt3d(tilt)
            // The "different plane": a soft indigo-black drop that the tilt + flip carry with the card.
            .shadow(24.dp, WalletShapes.extraLarge, clip = false, ambientColor = Indigo1000, spotColor = Indigo1000)
            // Past 90° the viewer sees the card's BACK, so the entire face — background, corner glow,
            // shine AND content — counter-rotates 180° (not just the text, else the glow would mirror to
            // the wrong corner and visibly jump back when the flip settles). flip is read only inside the
            // layer lambda → no recomposition.
            .graphicsLayer { rotationY = if (flip.value > 90f) 180f else 0f }
            .heroCardBackground(WalletShapes.extraLarge)
            .specularShine(tilt)
    } else {
        // "Fancy card" off: the same frosted glass as every other surface — flat, calm, no motion.
        Modifier.glassSurface(WalletShapes.extraLarge)
    }
    Column(
        Modifier.fillMaxWidth()
            .then(surface)
            .padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 20.dp),
    ) {
        if (showBalance) {
            BalanceHeroContent(fancy, availablePaise, accounts, expanded, { expanded = !expanded }, onUpdateBalance)
        } else {
            SpendHeroContent(
                fancy = fancy,
                showingBalance = shownFace,
                availablePaise = availablePaise,
                accounts = accounts,
                accountMonthSpend = accountMonthSpend,
                monthSpentPaise = monthSpentPaise,
                monthCount = monthCount,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                onFlip = { flipTo(!face) },
                monthBudget = monthBudget,
                onOpenBudgets = onOpenBudgets,
            )
        }
    }
}

/**
 * The 180° face flip: a half-turn around Y with a wider camera than the tilt's (a half-turn at the tilt's
 * 16dp would fisheye) and a slight scale-up at the edge-on midpoint, so the card reads as picked up off
 * its plane, flipped over, and set back down. Reads [flip] only in the layer block → animates without
 * recomposing.
 */
private fun Modifier.cardFlip(flip: Animatable<Float, AnimationVector1D>): Modifier = graphicsLayer {
    rotationY = flip.value
    cameraDistance = 48.dp.toPx()
    val lift = 1f + 0.05f * sin(Math.PI * (flip.value / 180.0)).toFloat()
    scaleX = lift
    scaleY = lift
}

@Composable
private fun ColumnScope.BalanceHeroContent(
    fancy: Boolean,
    availablePaise: Long,
    accounts: List<AccountBalance>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdateBalance: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(PillShape).background(GreenCredit))
            Spacer(Modifier.width(7.dp))
            Text("Available balance", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        }
        Row(
            Modifier.glassSurface(PillShape, strong = true, blur = false).clickable(onClick = onUpdateBalance)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("↻ Update balance", style = MaterialTheme.typography.labelMedium, color = White)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.clickable(
            enabled = accounts.isNotEmpty(),
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggleExpand,
        ),
        verticalAlignment = Alignment.Bottom,
    ) {
        val (rupees, frac) = Money.formatParts(availablePaise)
        EmbossedAmount(rupees, MaterialTheme.typography.displayLarge, fancy)
        Text(
            frac,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp),
            color = White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 3.dp),
        )
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            IconChevronDown(
                TextSecondary, size = 20.dp,
                modifier = Modifier.padding(bottom = 8.dp).rotate(chevronRotation),
            )
        }
    }
    AnimatedVisibility(visible = expanded && accounts.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            accounts.forEach { AccountChip(it.label, "at setup", it.baselinePaise, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ColumnScope.SpendHeroContent(
    fancy: Boolean,
    showingBalance: Boolean,
    availablePaise: Long,
    accounts: List<AccountBalance>,
    accountMonthSpend: List<AccountSpend>,
    monthSpentPaise: Long,
    monthCount: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onFlip: () -> Unit,
    monthBudget: BudgetStatus? = null,
    onOpenBudgets: () -> Unit = {},
) {
    val hasBalance = accounts.isNotEmpty()
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    // The per-account breakdown is only shown for the user's own set-up accounts (anchors). Balance face →
    // "at setup" baselines; spend face → how much was spent from each this month.
    val canExpand = hasBalance && if (showingBalance) accounts.isNotEmpty() else accountMonthSpend.isNotEmpty()

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No green live-dot here — that's a balance signal; the spend headline owns the card.
        Text(
            if (showingBalance) "Available balance" else "Spent this month",
            style = MaterialTheme.typography.labelLarge, color = TextSecondary,
        )
        if (hasBalance) {
            // Flip control — labels the face you'll switch TO (no "Hide"). The tap spins the whole card
            // (HeroBalanceCard owns the animation); the chips dropdown survives the flip.
            Row(
                Modifier.glassSurface(PillShape, strong = true, blur = false)
                    .clickable(onClick = onFlip)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconFlip(White, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showingBalance) "Show money spent" else "Show balance",
                    style = MaterialTheme.typography.labelMedium, color = White,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.clickable(
            enabled = canExpand,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggleExpand,
        ),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showingBalance) {
            val (rupees, frac) = Money.formatParts(availablePaise)
            EmbossedAmount(rupees, MaterialTheme.typography.displayLarge, fancy)
            Text(
                frac,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp),
                color = White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        } else {
            EmbossedAmount(Money.formatParts(monthSpentPaise).first, MaterialTheme.typography.displayLarge, fancy)
        }
        if (canExpand) {
            Spacer(Modifier.width(8.dp))
            IconChevronDown(
                TextSecondary, size = 20.dp,
                modifier = Modifier.padding(bottom = 8.dp).rotate(chevronRotation),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "$monthCount ${if (monthCount == 1) "payment" else "payments"} this month",
        style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
    )
    // The monthly-budget line — inserted BETWEEN the payments count and the account dropdown (below), so the
    // card chrome is otherwise untouched and the chips still expand beneath it. Spend-face only; a set cap only.
    if (!showingBalance && monthBudget != null) {
        Spacer(Modifier.height(14.dp))
        BudgetOnCard(status = monthBudget, fancy = fancy, onOpenBudgets = onOpenBudgets)
    }
    AnimatedVisibility(visible = expanded && canExpand) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showingBalance) {
                accounts.forEach { AccountChip(it.label, "at setup", it.baselinePaise, Modifier.weight(1f)) }
            } else {
                accountMonthSpend.forEach { AccountChip(it.label, "spent", it.spentPaise, Modifier.weight(1f)) }
            }
        }
    }
}

// Foil face for the hero's headline number — white rolling into lavender at the bottom, like stamped metal.
private val FoilBrush = Brush.verticalGradient(
    0.00f to White,
    0.55f to White,
    1.00f to Lavender300,
)

// Bevel shading for the embossed digits: a lit top edge rolling to a shaded bottom edge. Painted INSIDE
// the glyphs via SrcAtop (the stops bracket where the digits actually sit in the line box).
private val EmbossBevel = Brush.verticalGradient(
    0.00f to White.copy(alpha = 0.45f),
    0.30f to Color.Transparent,
    0.62f to Color.Transparent,
    0.95f to Indigo1000.copy(alpha = 0.50f),
)

// The hero headline's drop shadow, tuned for the 40sp number; the caption reuses a lighter one below.
private val HeroAmountShadow = Shadow(Indigo1000.copy(alpha = 0.65f), Offset(0f, 3f), blurRadius = 7f)

/**
 * Premium-card emboss, shared by the hero's headline number AND the on-card budget caption. ONE glyph layer
 * (an earlier offset-duplicate version read as "two numbers" when the shine swept over it): a foil-gradient
 * fill, a bevel gradient blended SrcAtop so it shades only inside the digits (lit top edge, shaded bottom
 * edge — the raised-metal read), and a dark drop shadow lifting them off the card. Offscreen compositing
 * scopes the SrcAtop to this text only. [foil]/[shadow] let the budget caption reuse the same emboss with a
 * band-tinted foil (amber/coral) and a lighter shadow at its smaller size.
 */
@Composable
private fun EmbossedAmount(
    text: String,
    style: TextStyle,
    fancy: Boolean,
    modifier: Modifier = Modifier,
    foil: Brush = FoilBrush,
    shadow: Shadow = HeroAmountShadow,
) {
    if (!fancy) {
        // Fancy card off — the plain white number, exactly like every other primary text.
        Text(text, style = style, color = TextPrimary, maxLines = 1, modifier = modifier)
        return
    }
    Text(
        text,
        style = style.merge(TextStyle(brush = foil, shadow = shadow)),
        maxLines = 1,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(EmbossBevel, blendMode = BlendMode.SrcAtop)
            },
    )
}

// ── On-card monthly budget: engraved caption + a V2 "recessed ridge" bar (a carved channel the aurora fills).
// Band-tinted foils so the caption's engraving turns amber/coral with the state, matching the big number's foil.
private val AmberFoil = Brush.verticalGradient(0.00f to Color(0xFFFFD79A), 0.55f to Amber500, 1.00f to Color(0xFFE6892B))
private val CoralFoil = Brush.verticalGradient(0.00f to Color(0xFFF6A7AB), 0.55f to Coral500, 1.00f to Color(0xFFD33F52))
private val BudgetCaptionShadow = Shadow(Indigo1000.copy(alpha = 0.60f), Offset(0f, 1.5f), blurRadius = 3f)
private val BudgetCaptionStyle = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFeatureSettings = "tnum",
)
// The carved-channel colours — darker than the card surface so the groove reads as recessed.
private val RidgeTop = Color(0xFF140D33)
private val RidgeBottom = Color(0xFF1A1350)

/**
 * The monthly-budget line that rides the spend card — an engraved caption (`of ₹<cap>` · `₹<left> left · <pct>%`,
 * or `Over by ₹X`) over the V2 recessed ridge. Whole block taps to Budgets. Colours follow the app budget rule
 * (calm aurora <80% → amber 80–99% → coral over), same as BudgetsScreen, and the caption's engraving tints too.
 */
@Composable
private fun BudgetOnCard(status: BudgetStatus, fancy: Boolean, onOpenBudgets: () -> Unit) {
    val fraction = (status.pct / 100f).coerceIn(0f, 1f)
    val fill: Brush = when {
        status.isOver -> SolidColor(Coral500)
        status.isNear -> SolidColor(Amber500)
        else -> AuroraBrush
    }
    val rightFoil: Brush = when {
        status.isOver -> CoralFoil
        status.isNear -> AmberFoil
        else -> FoilBrush
    }
    val limitStr = Money.formatParts(status.limitPaise).first
    val overage = -status.remainingPaise
    val rightStr = when {
        status.isOver && overage > 0L -> "Over by ${Money.formatParts(overage).first}"
        status.isOver -> "At your limit"   // exactly at the cap → no "Over by ₹0"
        else -> "${Money.formatParts(status.remainingPaise).first} left · ${status.pct}%"
    }
    Column(
        Modifier.fillMaxWidth().clip(WalletShapes.small).clickable(onClick = onOpenBudgets),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EmbossedAmount("of $limitStr", BudgetCaptionStyle, fancy, shadow = BudgetCaptionShadow)
            EmbossedAmount(rightStr, BudgetCaptionStyle, fancy, foil = rightFoil, shadow = BudgetCaptionShadow)
        }
        Spacer(Modifier.height(9.dp))
        BudgetRidge(fraction = fraction, fill = fill)
    }
}

/** The V2 recessed ridge: a dark carved channel (inner top-shadow + a faint lit lower lip) with the aurora
 *  fill sitting raised inside it (inset 2dp, a top gloss highlight) so it reads as liquid metal in the groove. */
@Composable
private fun BudgetRidge(fraction: Float, fill: Brush) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(PillShape)
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(RidgeTop, RidgeBottom)))            // the hollow
                // inner top-shadow: crisp + deep at the very top edge, fading fast → reads as a carved lip
                drawRect(Brush.verticalGradient(0.00f to Color.Black.copy(alpha = 0.62f), 0.32f to Color.Transparent))
                drawRect(Brush.verticalGradient(0.80f to Color.Transparent, 1.00f to White.copy(alpha = 0.08f)))       // lit lower lip
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .padding(2.dp)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(PillShape)
                    .background(fill)
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.00f to White.copy(alpha = 0.42f), 0.50f to Color.Transparent)) // raised gloss
                    },
            )
        }
    }
}

@Composable
fun AccountChip(label: String, sublabel: String, amountPaise: Long, modifier: Modifier = Modifier) {
    Column(
        // Chips sit on the hero, not the aurora canvas — plain fill (no Haze), else they'd punch a
        // frosted hole showing the blobs through the card.
        modifier.glassSurface(WalletShapes.medium, blur = false).padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            "$label · $sublabel",
            style = MaterialTheme.typography.labelSmall, color = TextSecondary,
        )
        Spacer(Modifier.height(2.dp))
        Text(Money.formatParts(amountPaise).first, style = MoneyRow, color = TextPrimary)
    }
}

// Stat value: Space Grotesk tabular, sized to the "invisible card" spec (23sp).
private val StatValue = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
    fontSize = 23.sp, fontFeatureSettings = "tnum",
)

/**
 * The two spend windows as an "invisible card": card-like padding, but NO fill / border / shadow (the
 * last `raisedGlass` slabs are gone). Two tap targets split by a single soft hairline; the left text edge
 * lines up with the recents glyph grid (20dp list inset + 12dp here ≈ the 24dp glyph's left edge). Both
 * still deep-link into Insights for their window.
 */
@Composable
fun StatPairFlat(
    leftPaise: Long, leftCount: Int, onLeft: () -> Unit,
    rightPaise: Long, rightCount: Int, onRight: () -> Unit,
    leftTitle: String = "This week", rightTitle: String = "This month",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        // NOTE: no clip() here — these tiles have no background, so a rounded clip only shapes the tap
        // ripple, and (with the left tile's text flush at x=0) its rounded top-left corner shaved the
        // first glyph ("T" of TODAY). A full-bounds ripple is fine for a flat tile.
        FlatStat(
            leftTitle, leftPaise, leftCount,
            Modifier.weight(1f).clickable(onClick = onLeft).padding(end = 16.dp),
        )
        Box(Modifier.fillMaxHeight().width(1.dp).background(White.copy(alpha = 0.07f)))
        FlatStat(
            rightTitle, rightPaise, rightCount,
            Modifier.weight(1f).clickable(onClick = onRight).padding(start = 16.dp),
        )
    }
}

@Composable
private fun FlatStat(title: String, amountPaise: Long, count: Int, modifier: Modifier) {
    Column(modifier) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
            color = TextTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(Money.formatParts(amountPaise).first, style = StatValue, color = TextPrimary, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            "$count ${if (count == 1) "payment" else "payments"}",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1,
        )
    }
}

/** Section header with a quiet text-link action (no bordered pill) — calm, on-language. */
@Composable
fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Text(
            action,
            style = MaterialTheme.typography.labelMedium, color = TextSecondary,
            modifier = Modifier
                .clip(WalletShapes.small)
                .clickable(onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun TransactionRow(ui: TxnRowUi, onClick: () -> Unit) {
    // Borderless row (no card) — a faint full-row tint on press, no ripple.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .drawBehind { if (pressed) drawRect(Color.White.copy(alpha = 0.05f)) }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PayeeMark(ui)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                    ),
                    color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ui.needsReview) {
                    // Quiet "we weren't sure" marker — a tiny dot, not a chip; most rows aren't flagged.
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.size(6.dp).clip(PillShape).background(WarnColor))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(rowSubtitle(ui), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val rupees = Money.formatParts(ui.amountPaise).first
            val (amountText, amountColor) = when {
                ui.isSelfTransfer -> rupees to TextSecondary
                ui.direction == Direction.CREDIT -> "+$rupees" to GreenCredit
                else -> "−$rupees" to White   // spend = white; red blended into the dark/magenta background
            }
            Text(amountText, style = MoneyRow, color = amountColor)
            Spacer(Modifier.height(3.dp))
            Text(ui.timeLabel, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
    }
}

/** The mono-mark, Option B "Bare" (the picked option): just a bare ~24dp white glyph — a person for a P2P
 *  payment, a briefcase for businesses / self. No halo disc, no corner arrow — direction already lives in
 *  the −₹ / +₹ money prefix. The person glyph is head-heavy, so it's nudged ~1dp down to optically centre. */
@Composable
private fun PayeeMark(ui: TxnRowUi) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        val glyph = White.copy(alpha = 0.92f)
        if (ui.isPerson && !ui.isSelfTransfer) {
            IconPerson(glyph, Modifier.offset(y = 1.dp), 24.dp)
        } else {
            IconBusiness(glyph, Modifier, 24.dp)
        }
    }
}

private fun rowSubtitle(ui: TxnRowUi): String = when {
    ui.isSelfTransfer -> "Transfer to self"
    ui.direction == Direction.CREDIT -> "Received"
    else -> ui.category ?: "Uncategorised"
}

/**
 * The shared transaction list: borderless [TransactionRow]s on the aurora, separated by a faint full-width
 * hairline — no container card, so it stays calm and on-language. Used by Home (Recent), Review, and the
 * All-transactions list, so a payment renders identically everywhere it appears.
 */
@Composable
fun TransactionListCard(rows: List<TxnRowUi>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            TransactionRow(row, onClick = { onOpen(row.id) })
        }
    }
}

/** A faint near-full-width hairline between borderless rows (inset to the row's 4dp side padding). */
@Composable
private fun RowDivider() {
    Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(HairlineColor))
}

/** Shimmer placeholder for the Recent list's first load — keeps the screen alive while Room warms up. */
@Composable
fun RecentSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { i ->
            if (i > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(Modifier.size(40.dp), PillShape)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    ShimmerBox(Modifier.fillMaxWidth(0.42f).height(11.dp), RoundedCornerShape(6.dp))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(Modifier.fillMaxWidth(0.62f).height(9.dp), RoundedCornerShape(6.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    ShimmerBox(Modifier.width(46.dp).height(11.dp), RoundedCornerShape(6.dp))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(Modifier.width(30.dp).height(8.dp), RoundedCornerShape(6.dp))
                }
            }
        }
    }
}

/** A single shimmering placeholder block — a moving aurora-neutral sheen over a faint base fill. */
@Composable
private fun ShimmerBox(modifier: Modifier, shape: Shape) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val p by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Restart),
        label = "sweep",
    )
    Box(
        modifier.clip(shape).background(Color.White.copy(alpha = 0.06f)).drawBehind {
            val sweep = size.width * 1.6f
            val x = -sweep + p * (size.width + sweep)
            drawRect(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + sweep, 0f),
                ),
            )
        },
    )
}
