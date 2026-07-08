package com.goushik.upiwallet.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goushik.upiwallet.domain.insights.ChartPoint
import com.goushik.upiwallet.ui.theme.AuroraColorStops
import com.goushik.upiwallet.ui.theme.auroraAt
import com.goushik.upiwallet.ui.theme.Indigo700
import com.goushik.upiwallet.ui.theme.Inter
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.SpaceGrotesk
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The hand-drawn spend chart (NO charting library). A single smooth line through the aurora gradient,
 * a soft area-fill beneath it, faint grid lines, a money y-axis on the left, a sparse x-axis along the
 * bottom, and a draggable marker (white-cored dot + colored ring) with a value tooltip. Sized by the
 * caller (the screen gives it weight so it fills the page); pass any height via [modifier].
 *
 * The curve is a MONOTONE cubic spline (Fritsch–Carlson tangents) — NOT plain Catmull-Rom. The two
 * mechanisms that guarantee the line never dips below 0 ("negative spend") or above the data between
 * samples:
 *   1. The extremum rule: at a local extremum (sign change in the slopes around a point — exactly what a
 *      zero-spend day flanked by spend days produces) the tangent is forced to 0, so the curve flattens
 *      to "kiss" that value instead of overshooting past it.
 *   2. The Fritsch–Carlson clamp (α²+β² > 9 → scale tangents by 3/√(α²+β²)) keeps each segment monotone
 *      between its endpoints, so the curve stays within the [yi, yi+1] band.
 * Belt-and-suspenders: a cubic Bézier is contained in the convex hull of its 4 control points, so we
 * additionally clamp the endpoints AND the two control-point y's into [topInset, baseline].
 *
 * The area fill is a vertical gradient anchored at the curve's PEAK (densest, ~opaque) fading to fully
 * transparent at the x-axis baseline — so the colour hugs the line and dissolves into the axis.
 */
@Composable
fun InsightsChart(
    points: List<ChartPoint>,
    selectedIndex: Int,            // -1 = nothing selected (defaults to the most recent bucket WITH spend)
    onSelectIndex: (Int) -> Unit,  // called with the nearest bucket index as the finger drags / taps
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val n = points.size

    Canvas(
        modifier
            // Tap + drag live in SEPARATE pointerInput blocks: detectTapGestures and detectDragGestures
            // each suspend on the same pointer stream, so chaining them in one block would starve the
            // second. Both map x → nearest bucket via the SAME plot insets as the draw mapping.
            .pointerInput(n) {
                val gutterPx = YAxisGutter.toPx()
                detectTapGestures { pickIndex(it.x, n, size.width.toFloat(), gutterPx, onSelectIndex) }
            }
            .pointerInput(n) {
                val gutterPx = YAxisGutter.toPx()
                detectDragGestures(
                    onDragStart = { pickIndex(it.x, n, size.width.toFloat(), gutterPx, onSelectIndex) },
                    onDrag = { change, _ ->
                        change.consume()
                        pickIndex(change.position.x, n, size.width.toFloat(), gutterPx, onSelectIndex)
                    },
                )
            },
    ) {
        drawChart(points, selectedIndex, textMeasurer)
    }
}

// ── layout constants ──
private val YAxisGutter: Dp = 46.dp   // left strip reserved for the money y-axis labels
private val XLabelBand: Dp = 22.dp    // bottom strip reserved for the day/month x-axis labels

// ── geometry helpers (shared by draw + touch so they stay in lock-step) ──
private fun plotLeft(gutterPx: Float) = gutterPx + 6f
private fun plotRight(w: Float) = w - (w * 0.04f + 10f)

/** Map a touch x → nearest bucket index, clamped to 0..lastIndex. Uses the SAME insets as draw. */
private fun pickIndex(x: Float, n: Int, w: Float, gutterPx: Float, onSelectIndex: (Int) -> Unit) {
    if (n <= 1) { onSelectIndex(0); return }
    val left = plotLeft(gutterPx)
    val right = plotRight(w)
    val span = (right - left).coerceAtLeast(1f)
    val frac = ((x - left) / span).coerceIn(0f, 1f)
    onSelectIndex((frac * (n - 1)).roundToInt().coerceIn(0, n - 1))
}

private fun DrawScope.drawChart(
    points: List<ChartPoint>,
    selectedIndex: Int,
    textMeasurer: TextMeasurer,
) {
    val n = points.size
    val w = size.width
    val h = size.height
    if (n == 0 || w <= 0f || h <= 0f) return

    val gutterPx = YAxisGutter.toPx()
    val labelBand = XLabelBand.toPx()
    val left = plotLeft(gutterPx)
    val right = plotRight(w)
    val xSpan = (right - left).coerceAtLeast(1f)

    val topInset = h * 0.12f          // headroom for the peak + its tooltip
    val baseline = h - labelBand
    val plotH = (baseline - topInset).coerceAtLeast(1f)

    val maxAmount = points.maxOf { it.amountPaise }.coerceAtLeast(0L)
    // A "nice" axis ceiling (1/2/5 × 10^k per division) so gridlines land on round money values, and
    // the peak sits a touch below the top. 3 divisions → 4 gridlines (incl. 0 at the baseline).
    val divisions = 3
    val step = niceStepPaise(maxAmount, divisions)
    val niceMax = (step * divisions).coerceAtLeast(maxAmount)

    fun sx(i: Int): Float = if (n == 1) (left + right) / 2f else left + xSpan * i / (n - 1)
    fun sy(amount: Long): Float {
        if (niceMax <= 0L) return baseline          // all-zero → flat baseline (no divide-by-zero)
        val frac = amount.toFloat() / niceMax.toFloat()
        return (baseline - frac * plotH).coerceIn(topInset, baseline)
    }

    // ── grid + money y-axis (4 hairlines; labels right-aligned in the left gutter) ──
    val yStyle = axisLabelStyle()
    for (g in 0..divisions) {
        val gy = topInset + plotH * g / divisions
        drawLine(
            color = White.copy(alpha = 0.05f),
            start = Offset(left, gy),
            end = Offset(right, gy),
            strokeWidth = 1f,
        )
        val labelText = when {
            step > 0L -> compactRupees(step * (divisions - g))   // top = niceMax … bottom = ₹0
            g == divisions -> "₹0"                               // empty range → only the baseline ₹0
            else -> ""
        }
        if (labelText.isNotEmpty()) {
            val layout = textMeasurer.measure(labelText, yStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (left - 8f - layout.size.width).coerceAtLeast(0f),
                    gy - layout.size.height / 2f,
                ),
            )
        }
    }

    // Screen-space points.
    val px = FloatArray(n) { sx(it) }
    val py = FloatArray(n) { sy(points[it].amountPaise) }

    val strokePx = 3.dp.toPx()
    val lineBrush = Brush.linearGradient(
        colorStops = AuroraColorStops,
        start = Offset(left, 0f),
        end = Offset(right, 0f),
    )

    if (n == 1) {
        // Degenerate: single bucket (e.g. a 1-day custom range) → flat baseline + a dot at its value.
        drawLine(White.copy(alpha = 0.08f), Offset(left, baseline), Offset(right, baseline), 1.5f)
        drawMarker(points, 0, px[0], py[0], topInset, baseline, w, textMeasurer)
        return
    }

    // ── Monotone cubic (Fritsch–Carlson) tangents in SCREEN space (the y-flip is affine, so
    //    monotonicity is preserved). x is evenly spaced → constant dx. ──
    val dx = xSpan / (n - 1)
    val slope = FloatArray(n - 1) { (py[it + 1] - py[it]) / dx }
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (slope[i - 1] * slope[i] <= 0f) 0f else (slope[i - 1] + slope[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            m[i] = 0f; m[i + 1] = 0f
        } else {
            val a = m[i] / slope[i]
            val b = m[i + 1] / slope[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * slope[i]
                m[i + 1] = t * b * slope[i]
            }
        }
    }

    fun clampY(y: Float) = y.coerceIn(topInset, baseline)

    val curve = Path().apply {
        moveTo(px[0], clampY(py[0]))
        for (i in 0 until n - 1) {
            val hSeg = px[i + 1] - px[i]
            val cp1y = clampY(py[i] + m[i] * hSeg / 3f)
            val cp2y = clampY(py[i + 1] - m[i + 1] * hSeg / 3f)
            cubicTo(
                px[i] + hSeg / 3f, cp1y,
                px[i + 1] - hSeg / 3f, cp2y,
                px[i + 1], clampY(py[i + 1]),
            )
        }
    }

    // ── area fill: close the curve down to the baseline, fill with a vertical aurora wash anchored at
    //    the curve's PEAK (~opaque, hugging the line) fading to fully transparent at the x-axis. ──
    val fill = Path().apply {
        addPath(curve)
        lineTo(px[n - 1], baseline)
        lineTo(px[0], baseline)
        close()
    }
    val peakY = py.minOrNull() ?: topInset
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            0.0f to Magenta500.copy(alpha = 0.50f),
            0.45f to Violet500.copy(alpha = 0.20f),
            1.0f to Color.Transparent,
            startY = peakY,
            endY = baseline,
        ),
    )

    // ── the line itself ──
    drawPath(
        path = curve,
        brush = lineBrush,
        style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // ── x-axis labels (only the labelled subset; xLabel == "" prints nothing) ──
    for (i in 0 until n) {
        val label = points[i].xLabel
        if (label.isEmpty()) continue
        val layout = textMeasurer.measure(label, yStyle)
        val lx = (px[i] - layout.size.width / 2f).coerceIn(left - 4f, w - layout.size.width - 2f)
        drawText(layout, topLeft = Offset(lx, h - layout.size.height - 2f))
    }

    // ── selection marker. When nothing is selected, default to the most recent bucket that HAS spend —
    //    NOT the literal last bucket: every window runs to the period END (a future, zero bucket). ──
    val active = if (selectedIndex in 0 until n) {
        selectedIndex
    } else {
        points.indexOfLast { it.amountPaise > 0L }.takeIf { it >= 0 } ?: (n - 1)
    }
    drawMarker(points, active, px[active], py[active], topInset, baseline, w, textMeasurer)
}

private fun DrawScope.drawMarker(
    points: List<ChartPoint>,
    index: Int,
    cx: Float,
    cy: Float,
    topInset: Float,
    baseline: Float,
    w: Float,
    textMeasurer: TextMeasurer,
) {
    val ringColor = auroraAt(if (w <= 0f) 0f else cx / w)

    drawLine(
        color = White.copy(alpha = 0.12f),
        start = Offset(cx, topInset),
        end = Offset(cx, baseline),
        strokeWidth = 1f,
    )

    drawCircle(ringColor, radius = 6.dp.toPx(), center = Offset(cx, cy))
    drawCircle(White, radius = 4.dp.toPx(), center = Offset(cx, cy))

    // ── tooltip bubble ──
    val amountLine = Money.format(points[index].amountPaise)
    val subLine = points[index].xLabel.ifEmpty { deriveLabel(points[index].bucketStartMs) }

    val amountLayout = textMeasurer.measure(amountLine, tooltipAmountStyle())
    val subLayout = textMeasurer.measure(subLine, tooltipSubStyle())

    val padH = 10.dp.toPx()
    val padV = 7.dp.toPx()
    val gap = 2.dp.toPx()
    val contentW = maxOf(amountLayout.size.width, subLayout.size.width).toFloat()
    val bubbleW = contentW + padH * 2
    val bubbleH = amountLayout.size.height + subLayout.size.height + gap + padV * 2

    val bx = (cx - bubbleW / 2f).coerceIn(2f, (w - bubbleW - 2f).coerceAtLeast(2f))

    val dotR = 6.dp.toPx()
    val above = cy - dotR - 8.dp.toPx() - bubbleH
    val by = if (above >= 2f) above else (cy + dotR + 8.dp.toPx())

    val radius = CornerRadius(10.dp.toPx(), 10.dp.toPx())
    drawRoundRect(
        color = Indigo700.copy(alpha = 0.92f),
        topLeft = Offset(bx, by),
        size = Size(bubbleW, bubbleH),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = White.copy(alpha = 0.12f),
        topLeft = Offset(bx, by),
        size = Size(bubbleW, bubbleH),
        cornerRadius = radius,
        style = Stroke(1.dp.toPx()),
    )

    val textX = bx + padH
    drawText(
        amountLayout,
        topLeft = Offset(textX + (contentW - amountLayout.size.width) / 2f, by + padV),
    )
    drawText(
        subLayout,
        topLeft = Offset(
            textX + (contentW - subLayout.size.width) / 2f,
            by + padV + amountLayout.size.height + gap,
        ),
    )
}

private fun deriveLabel(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))

// ── money axis helpers ──

/** Round the per-division amount up to a "nice" 1/2/5 × 10^k value (in paise). */
private fun niceStepPaise(maxAmount: Long, divisions: Int): Long {
    if (maxAmount <= 0L || divisions <= 0) return 0L
    val raw = maxAmount.toDouble() / divisions
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val niceNorm = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return (niceNorm * mag).toLong().coerceAtLeast(1L)
}

/** Compact money for the tight y-axis gutter: ₹0 / ₹500 / ₹1.5k / ₹2L (full amount stays in the headline). */
private fun compactRupees(paise: Long): String {
    val rs = paise / 100
    return when {
        rs < 1000 -> "₹$rs"
        rs < 100_000 -> "₹${trimOne(rs / 1000.0)}k"
        else -> "₹${trimOne(rs / 100_000.0)}L"
    }
}

private fun trimOne(d: Double): String {
    val r = (d * 10).roundToInt() / 10.0
    return if (r == floor(r)) r.toInt().toString() else r.toString()
}

// ── text styles ──
private fun axisLabelStyle() = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.Medium,
    fontSize = 10.sp, color = TextTertiary,
)

private fun tooltipAmountStyle() = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp, color = White, textAlign = TextAlign.Center,
    fontFeatureSettings = "tnum",
)

private fun tooltipSubStyle() = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.Medium,
    fontSize = 10.sp, color = White.copy(alpha = 0.6f), textAlign = TextAlign.Center,
)

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(widthDp = 360, heightDp = 420, backgroundColor = 0xFF140C38, showBackground = true)
@Composable
private fun InsightsChartPreview() {
    val rupees = listOf(120, 0, 340, 90, 0, 0, 520, 210, 60, 0, 410, 880, 150, 70)
    val day = 24L * 60 * 60 * 1000
    val start = 1_717_200_000_000L
    val pts = rupees.mapIndexed { i, r ->
        ChartPoint(
            bucketStartMs = start + i * day,
            amountPaise = r * 100L,
            xLabel = if (i % 3 == 0) "${i + 1} Jun" else "",
        )
    }
    InsightsChart(points = pts, selectedIndex = -1, onSelectIndex = {}, modifier = Modifier.fillMaxSize())
}
