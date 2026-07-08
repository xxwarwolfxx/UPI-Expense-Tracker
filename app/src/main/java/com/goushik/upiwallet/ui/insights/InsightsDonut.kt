package com.goushik.upiwallet.ui.insights

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.insights.CategorySlice
import com.goushik.upiwallet.domain.insights.StairStep
import com.goushik.upiwallet.domain.insights.buildStairSteps
import com.goushik.upiwallet.ui.home.rememberDeviceTilt
import com.goushik.upiwallet.ui.theme.GlassSpecular
import com.goushik.upiwallet.ui.theme.GlassSurface
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.SpaceGrotesk
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.auroraAt
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.ui.theme.rememberReduceMotion
import com.goushik.upiwallet.util.Money
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ── A distinct hue per category (the map's categoryColor collapses 12→6; the donut needs one each). ──
private val DonutColors: Map<Category, Color> = mapOf(
    Category.BILLS to Color(0xFF6D59DB),
    Category.SHOPPING to Color(0xFFE8249D),
    Category.FOOD to Color(0xFFF6A037),
    Category.TRANSPORT to Color(0xFF38BDF8),
    Category.GROCERIES to Color(0xFF3DDC97),
    Category.ENTERTAINMENT to Color(0xFFED6266),
    Category.HEALTH to Color(0xFF2DD4BF),
    Category.RENT to Color(0xFFA78BFA),
    Category.TRAVEL to Color(0xFFFBBF24),
    Category.SUBSCRIPTIONS to Color(0xFF818CF8),
    Category.OTHER to Color(0xFF8B95A8),
    Category.SELF_TRANSFER to Color(0xFF8B95A8), // never appears in spend, but keep the map total
)
private val UncategorizedColor = Color(0xFF6B6486)

/** One distinct colour per category; unknown/null → the muted Uncategorized hue. */
fun categoryDonutColor(label: String?): Color =
    Category.fromLabel(label)?.let { DonutColors[it] } ?: UncategorizedColor

private val CentreTotal = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, fontFeatureSettings = "tnum",
)
private val LegendMoney = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, fontFeatureSettings = "tnum",
)

/**
 * Insights "Categories" view (Phase D): a glossy by-category donut with the total ONLY in its centre, a
 * 2D·3D toggle (3D = the deferred spending staircase), and a ranked, tappable legend below. A category row
 * opens that category's transactions; the Uncategorized row nudges into Review.
 */
@Composable
fun InsightsCategoriesView(
    slices: List<CategorySlice>,
    totalPaise: Long,
    rangeLabel: String,
    txnCount: Int,
    onOpenCategory: (String) -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var is3D by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    // The stand-up morph: 0 = flat ring, 1 = full staircase. At 0 the staircase IS a flat filled donut,
    // so the 2D↔3D swap happens while the two look the same — the ring visibly rises into the steps.
    val rise by animateFloatAsState(
        if (is3D) 1f else 0f,
        tween(if (reduceMotion) 0 else 600, easing = FastOutSlowInEasing),
        label = "stairRise",
    )
    Column(modifier.verticalScroll(rememberScrollState())) {
        // Fixed-height stage for BOTH modes: the staircase's flat ring starts at exactly the 2D donut's
        // radii/centre inside the same box, so the 2D↔3D swap is pixel-aligned and the morph is seamless.
        Box(
            Modifier.fillMaxWidth().height(270.dp).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (is3D || rise > 0.01f) {
                StaircaseDonut(slices, totalPaise, rangeLabel, txnCount, rise)
            } else {
                GlossyDonut(slices, totalPaise, rangeLabel, txnCount)
            }
            DimensionToggle(is3D, Modifier.align(Alignment.TopEnd)) { is3D = it }
        }
        Spacer(Modifier.height(10.dp))
        if (slices.isEmpty()) {
            Text(
                "No spend in this range yet",
                style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        } else {
            slices.forEachIndexed { i, slice ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
                LegendRow(slice, totalPaise, onOpenCategory, onOpenReview)
            }
        }
    }
}

@Composable
private fun GlossyDonut(slices: List<CategorySlice>, totalPaise: Long, rangeLabel: String, txnCount: Int) {
    Box(Modifier.size(214.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(214.dp)) {
            val strokeW = size.minDimension * 0.105f
            val radius = (size.minDimension - strokeW) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val tl = Offset(c.x - radius, c.y - radius)
            val box = Size(radius * 2f, radius * 2f)
            val total = slices.sumOf { it.spentPaise }.coerceAtLeast(1L).toFloat()
            val rOut = radius + strokeW / 2f
            val rIn = radius - strokeW / 2f
            val borderW = 1.5.dp.toPx()
            // Frosted-glass BODY of the whole ring — the same translucent-white base as the Chart/Categories/Map
            // toggle pill (glass.surface), so the aurora still reads through the ring.
            drawArc(GlassSurface, -90f, 360f, useCenter = false, topLeft = tl, size = box, style = Stroke(strokeW))
            var start = -90f
            slices.forEach { s ->
                val sweep = 360f * (s.spentPaise / total)
                val col = categoryDonutColor(s.label)
                // Translucent colour fill — the toggle's 30% selected fill: glass, the background stays visible …
                drawArc(col.copy(alpha = 0.32f), start, sweep, useCenter = false, topLeft = tl, size = box, style = Stroke(strokeW))
                // … and a SOLID full-strength colour border framing the segment (the toggle's 1dp violet border):
                // this is where each category colour reads PROMINENT, while the fill itself stays see-through.
                val outline = Path().apply {
                    arcTo(Rect(c, rOut), start, sweep, forceMoveTo = true)
                    arcTo(Rect(c, rIn), start + sweep, -sweep, forceMoveTo = false)
                    close()
                }
                drawPath(outline, col, style = Stroke(borderW))
                start += sweep
            }
            // A tight top-left sheen so the glass still catches the light — kept subtle, since the colour borders
            // carry the punch now and a broad veil would only wash them out.
            drawArc(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.10f to GlassSpecular.copy(alpha = 0.38f),
                        0.26f to GlassSpecular.copy(alpha = 0.05f),
                        0.42f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    start = tl,
                    end = Offset(tl.x + box.width, tl.y + box.height),
                ),
                startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = tl, size = box, style = Stroke(strokeW),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Money.formatParts(totalPaise).first, style = CentreTotal, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(
                rangeLabel.ifEmpty { "This month" }.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp), color = TextTertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text("$txnCount ${if (txnCount == 1) "payment" else "payments"}", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
    }
}

@Composable
private fun LegendRow(
    slice: CategorySlice,
    totalPaise: Long,
    onOpenCategory: (String) -> Unit,
    onOpenReview: () -> Unit,
) {
    val isUncat = slice.label == null
    val pct = if (totalPaise > 0) ((slice.spentPaise * 100f) / totalPaise).roundToInt() else 0
    Row(
        Modifier.fillMaxWidth()
            .clickable { if (isUncat) onOpenReview() else onOpenCategory(slice.label!!) }
            .padding(horizontal = 4.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(categoryDonutColor(slice.label)))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                slice.label ?: "Uncategorized",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isUncat) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "${slice.count} left · Review →",
                    style = MaterialTheme.typography.labelMedium, color = WarnColor, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(Money.formatParts(slice.spentPaise).first, style = LegendMoney, color = TextPrimary)
        Spacer(Modifier.width(10.dp))
        Text("$pct%", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
    }
}

/** Small 2D·3D segmented toggle (top-right of the ring). 3D = the deferred spending staircase. */
@Composable
private fun DimensionToggle(is3D: Boolean, modifier: Modifier = Modifier, onSelect: (Boolean) -> Unit) {
    Row(
        modifier.glassSurface(PillShape, blur = false).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DimSegment("2D", !is3D) { onSelect(false) }
        DimSegment("3D", is3D) { onSelect(true) }
    }
}

@Composable
private fun DimSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape)
            .then(if (selected) Modifier.background(Violet500.copy(alpha = 0.30f)).border(1.dp, Violet500, PillShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) White else TextSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3D "spending staircase" (from the design sketch): the donut stands up into a circular
// staircase — each category an extruded ring step, biggest = tallest, descending clockwise to the
// smallest, with the wrap riser back up to the top step. Hand-rolled 3D like the map skyline: project
// (ρ, θ, z) onto a squished ellipse, painter-sort the faces back-to-front, shade each face by a fixed
// top-left light for the gloss. Drag horizontally to orbit; the hero card's gyro tilt sways it.
// ─────────────────────────────────────────────────────────────────────────────

// Centre info floats over whatever step is behind it as the staircase orbits — a soft dark halo keeps it
// legible even over the lightest tread. The total is the headline of the 3D view (red-pen: "come up more").
private val CentreShadow = Shadow(Color(0xE6000000), Offset(0f, 1.5f), blurRadius = 10f)
private val CentreTotal3D = CentreTotal.copy(fontSize = 30.sp, shadow = CentreShadow)

/**
 * One continuous surface of the staircase — a whole wall run, tread, or riser as a SINGLE path. Glass
 * lives or dies on this: per-segment quads seam visibly at translucent alphas, so every surface is one
 * fill + one glow gradient + one screen-anchored sheen, with nothing to seam. [order] groups back→front
 * (inner walls < risers < treads < outer walls); [depth] sorts within a group.
 */
private class StairPiece(
    val order: Int, val depth: Float, val path: Path,
    val fill: Color, val glow: Brush?, val sheen: Brush?,
    val rim: Path?, val rimColor: Color?,
)

private fun fl(a: Float, b: Float, t: Float): Float = a + (b - a) * t

@Composable
private fun StaircaseDonut(
    slices: List<CategorySlice>,
    totalPaise: Long,
    rangeLabel: String,
    txnCount: Int,
    rise: Float,
) {
    val steps = remember(slices) { buildStairSteps(slices) }
    var yaw by remember { mutableFloatStateOf(0f) }
    // The hero card's motion hook — the staircase tilts/sways with the phone like the card does.
    val tilt = rememberDeviceTilt()
    Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    // Horizontal drag = orbit (vertical stays with the page scroll).
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        yaw += dragAmount / 140.dp.toPx()
                    }
                },
        ) {
            // yaw + tilt are read HERE, in the draw phase → orbit/gyro redraw without recomposing.
            // Strong sway (red-pen: "more tilt") — a phone roll swings the orbit ~±22°, pitch leans the ring.
            val gyroYaw = if (tilt.active) tilt.rollDeg / 28f else 0f
            val gyroPitch = if (tilt.active) tilt.pitchDeg / 120f else 0f
            drawStaircase(steps, rise, yaw + gyroYaw, gyroPitch)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Starts at the 2D donut's exact centre/size and floats up + grows with the rise, landing a
            // touch above the hole's optical centre (red-pen: "the text should come up").
            modifier = Modifier
                .offset(y = (6f * rise).dp)
                .graphicsLayer {
                    val s = fl(28f / 30f, 1f, rise)
                    scaleX = s
                    scaleY = s
                },
        ) {
            Text(Money.formatParts(totalPaise).first, style = CentreTotal3D, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                rangeLabel.ifEmpty { "This month" }.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp, shadow = CentreShadow),
                color = TextSecondary,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                "$txnCount ${if (txnCount == 1) "payment" else "payments"}",
                style = MaterialTheme.typography.bodySmall.copy(shadow = CentreShadow), color = TextSecondary,
            )
        }
    }
}

/** Fixed light from the top-left of the screen (the 2D donut's sheen direction). */
private const val LightRad = (-3.0 * PI / 4.0).toFloat()

private fun DrawScope.drawStaircase(steps: List<StairStep>, rise: Float, yawRad: Float, pitch: Float) {
    if (steps.isEmpty()) return
    val cx = size.width / 2f
    // The morph: at rise=0 the ring IS the 2D donut — same centre, same radii (GlossyDonut's 214dp box,
    // 10.5% stroke), opaque category colours. As it rises it re-centres, widens, and turns to glass.
    val stroke2D = 214.dp.toPx() * 0.105f
    val r2D = (214.dp.toPx() - stroke2D) / 2f
    val outerFull = min(size.width * 0.40f, size.height * 0.60f)
    val outerR = fl(r2D + stroke2D / 2f, outerFull, rise)
    val innerR = fl(r2D - stroke2D / 2f, outerFull * 0.56f, rise)
    val cy = fl(size.height / 2f, size.height * 0.56f, rise)
    // Flat ring (squish 1, height 0) at rise=0 → standing staircase at rise=1; gyro pitch nudges the lean.
    val squish = (1f - rise * 0.48f + pitch).coerceIn(0.40f, 1f)
    val maxH = outerFull * 0.46f * rise

    fun px(rho: Float, theta: Float): Float = cx + rho * cos(theta + yawRad)
    fun py(rho: Float, theta: Float, z: Float): Float = cy + rho * sin(theta + yawRad) * squish - z

    // Soft ground shadow under the ring — the staircase floats on the aurora like the hero card.
    if (rise > 0.05f) {
        val midR = (outerR + innerR) / 2f * 1.03f
        drawOval(
            Color.Black.copy(alpha = 0.20f * rise),
            topLeft = Offset(cx - midR, cy + 3.dp.toPx() - midR * squish),
            size = Size(midR * 2f, midR * 2f * squish),
            style = Stroke((outerR - innerR) * 1.25f),
        )
    }

    // Sampled-arc geometry: each surface is ONE continuous path (per-segment quads seam at glass alphas).
    val segMax = (4.0 * PI / 180.0).toFloat()
    val pieces = ArrayList<StairPiece>(steps.size * 4)
    // The light tubes: one spine per step (the arc through the middle of its glass, at half height),
    // stroked with real blur — the category colour glowing out of the glass, like the chart/map glows.
    val spines = ArrayList<Pair<Path, Color>>(steps.size)

    // One screen-anchored sheen shared by every piece — because the gradient lives in screen space, it
    // flows continuously across separate paths: the hero card's diagonal aurora-tinted light, seam-free.
    val sheenBrush = if (rise > 0.05f) Brush.linearGradient(
        colorStops = arrayOf(
            0f to White.copy(alpha = 0.26f * rise),
            0.30f to lerp(White, auroraAt(0.22f), 0.45f).copy(alpha = 0.10f * rise),
            0.60f to Color.Transparent,
            1f to Color.Transparent,
        ),
        start = Offset(cx - outerR, cy - outerR * squish - maxH),
        end = Offset(cx + outerR, cy + outerR * squish),
    ) else null

    steps.forEachIndexed { i, step ->
        val base = categoryDonutColor(step.label)
        val h = maxH * step.height01
        val n = ceil((step.endRad - step.startRad) / segMax).toInt().coerceAtLeast(2)
        val da = (step.endRad - step.startRad) / n
        fun ang(k: Int) = step.startRad + k * da
        val midDepth = sin((step.startRad + step.endRad) / 2f + yawRad)

        // Tread: one annular-sector path at height h (inner arc out, outer arc back).
        val tread = Path()
        tread.moveTo(px(innerR, ang(0)), py(innerR, ang(0), h))
        for (k in 1..n) tread.lineTo(px(innerR, ang(k)), py(innerR, ang(k), h))
        for (k in n downTo 0) tread.lineTo(px(outerR, ang(k)), py(outerR, ang(k), h))
        tread.close()
        val treadRim = Path()
        treadRim.moveTo(px(outerR, ang(0)), py(outerR, ang(0), h))
        for (k in 1..n) treadRim.lineTo(px(outerR, ang(k)), py(outerR, ang(k), h))
        treadRim.moveTo(px(innerR, ang(0)), py(innerR, ang(0), h))
        for (k in 1..n) treadRim.lineTo(px(innerR, ang(k)), py(innerR, ang(k), h))
        pieces += StairPiece(
            2, midDepth, tread,
            // rise=0 starts at the 2D glass donut's fill alpha (0.32), so the 2D↔3D swap doesn't pop.
            lerp(base, White, 0.12f * rise).copy(alpha = fl(0.32f, 0.36f, rise)),
            null, sheenBrush, treadRim, White.copy(alpha = 0.22f * rise),
        )

        // The step's light spine — the neon inside its glass tube.
        if (rise > 0.05f && h > 0.5f) {
            val midRho = (innerR + outerR) / 2f
            val zMid = h * 0.55f
            val spine = Path()
            spine.moveTo(px(midRho, ang(0)), py(midRho, ang(0), zMid))
            for (k in 1..n) spine.lineTo(px(midRho, ang(k)), py(midRho, ang(k), zMid))
            spines += spine to base
        }

        // Walls: contiguous visible runs — outer on the front half, inner on the back half — each run
        // ONE path (top polyline at h, back along the ground), with ONE bottom→top glow gradient: the
        // category colour pooled inside the glass, fading up. No segment seams anywhere.
        if (h > 0.5f) {
            for (innerSide in booleanArrayOf(false, true)) {
                val rho = if (innerSide) innerR else outerR
                var k = 0
                while (k < n) {
                    val facing = sin(ang(k) + da / 2f + yawRad)
                    val visible = if (innerSide) facing < 0.04f else facing > -0.04f
                    if (!visible) { k++; continue }
                    var k2 = k
                    while (k2 < n) {
                        val f2 = sin(ang(k2) + da / 2f + yawRad)
                        if (if (innerSide) f2 < 0.04f else f2 > -0.04f) k2++ else break
                    }
                    val wall = Path()
                    var bottomMax = Float.MIN_VALUE
                    var topMin = Float.MAX_VALUE
                    wall.moveTo(px(rho, ang(k)), py(rho, ang(k), h))
                    for (q in k..k2) {
                        val y = py(rho, ang(q), h)
                        topMin = min(topMin, y)
                        if (q > k) wall.lineTo(px(rho, ang(q)), y)
                    }
                    for (q in k2 downTo k) {
                        val y = py(rho, ang(q), 0f)
                        bottomMax = max(bottomMax, y)
                        wall.lineTo(px(rho, ang(q)), y)
                    }
                    wall.close()
                    val rimTop = Path()
                    rimTop.moveTo(px(rho, ang(k)), py(rho, ang(k), h))
                    for (q in (k + 1)..k2) rimTop.lineTo(px(rho, ang(q)), py(rho, ang(q), h))
                    val glowA = (if (innerSide) 0.18f else 0.30f) * rise
                    val glow = if (glowA > 0.02f && bottomMax > topMin) Brush.linearGradient(
                        colorStops = arrayOf(0f to base.copy(alpha = glowA), 1f to base.copy(alpha = glowA * 0.05f)),
                        start = Offset(cx, bottomMax),
                        end = Offset(cx, topMin),
                    ) else null
                    val runDepth = sin((ang(k) + ang(k2)) / 2f + yawRad)
                    pieces += StairPiece(
                        if (innerSide) 0 else 3, runDepth, wall,
                        base.copy(alpha = if (innerSide) 0.18f else 0.26f),
                        glow, if (innerSide) null else sheenBrush,
                        rimTop, White.copy(alpha = (if (innerSide) 0.12f else 0.20f) * rise),
                    )
                    k = k2 + 1
                }
            }
        }

        // Riser between this step and the next (wrapping to the first — the tall back wall of the top
        // step that makes it read as a staircase, not a bar chart). A single quad → nothing to seam.
        val next = steps[(i + 1) % steps.size]
        val hLow = maxH * min(step.height01, next.height01)
        val hHigh = maxH * max(step.height01, next.height01)
        if (hHigh - hLow > 0.5f) {
            val b = step.endRad
            val tallerColor = categoryDonutColor((if (next.height01 > step.height01) next else step).label)
            val riser = Path()
            riser.moveTo(px(innerR, b), py(innerR, b, hHigh))
            riser.lineTo(px(outerR, b), py(outerR, b, hHigh))
            riser.lineTo(px(outerR, b), py(outerR, b, hLow))
            riser.lineTo(px(innerR, b), py(innerR, b, hLow))
            riser.close()
            val rim = Path()
            rim.moveTo(px(innerR, b), py(innerR, b, hHigh))
            rim.lineTo(px(outerR, b), py(outerR, b, hHigh))
            val yBot = max(py(innerR, b, hLow), py(outerR, b, hLow))
            val yTop = min(py(innerR, b, hHigh), py(outerR, b, hHigh))
            val glow = if (rise > 0.05f && yBot > yTop) Brush.linearGradient(
                colorStops = arrayOf(0f to tallerColor.copy(alpha = 0.26f * rise), 1f to tallerColor.copy(alpha = 0.04f)),
                start = Offset(cx, yBot), end = Offset(cx, yTop),
            ) else null
            pieces += StairPiece(
                1, sin(b + yawRad), riser, tallerColor.copy(alpha = 0.24f),
                glow, null, rim, White.copy(alpha = 0.16f * rise),
            )
        }
    }

    // Back→front in coarse groups (inner walls, risers, treads, outer walls), by depth within a group.
    // Glass is forgiving: at these alphas a blend-order miss is invisible, seams were the real enemy.
    // The light tubes slot INTO the sandwich: back glass → bloom + hot core → front glass → a faint
    // bloom over the top, so the colour visibly bleeds out of the glass.
    pieces.sortWith(compareBy({ it.order }, { it.depth }))
    val rimW = 1.2.dp.toPx()
    val tubeW = outerR - innerR
    fun drawPiece(p: StairPiece) {
        drawPath(p.path, p.fill)
        p.glow?.let { drawPath(p.path, it) }
        p.sheen?.let { drawPath(p.path, it) }
        p.rim?.let { r -> p.rimColor?.let { c -> if (c.alpha > 0.01f) drawPath(r, c, style = Stroke(rimW)) } }
    }
    pieces.forEach { if (it.order <= 1) drawPiece(it) }
    neonSpines(spines, tubeW, rise)
    pieces.forEach { if (it.order >= 2) drawPiece(it) }
}

/**
 * The light inside the glass — quiet, stays within the tube, PURE category colour only (no bleed pass
 * over the front glass, and no whitened "hot core": both read as a white band running through the whole
 * object on the muted grey Other step). Drawn via the native paint's [BlurMaskFilter] (Compose has no
 * blurred stroke primitive).
 */
private fun DrawScope.neonSpines(spines: List<Pair<Path, Color>>, tubeW: Float, alphaScale: Float) {
    if (alphaScale <= 0.03f || spines.isEmpty()) return
    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.style = PaintingStyle.Stroke
        val fp = paint.asFrameworkPaint()
        fp.strokeCap = android.graphics.Paint.Cap.ROUND
        fp.maskFilter = BlurMaskFilter(10.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        spines.forEach { (path, color) ->
            paint.strokeWidth = tubeW * 0.70f
            paint.color = color.copy(alpha = 0.15f * alphaScale)
            canvas.drawPath(path, paint)
        }
    }
}
