package com.goushik.upiwallet.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ─────────────────────────────────────────────────────────────────────────────
// Primitives — mirrored from design-system/tokens.json (canonical: style-guide.html).
// Dark-only system; do not introduce stray hexes elsewhere — reference these.
// ─────────────────────────────────────────────────────────────────────────────
val Indigo1000 = Color(0xFF0C0726)  // deepest scrim — the Home aurora-canvas corners
val Indigo950 = Color(0xFF140C38)
val Indigo900 = Color(0xFF1B1147)
val Indigo800 = Color(0xFF201560)
val Indigo700 = Color(0xFF2A1F6E)
val Indigo600 = Color(0xFF3A2F87)
val Indigo500 = Color(0xFF665BA0)

val Amber500 = Color(0xFFF6A037)   // aurora hot end
val Coral500 = Color(0xFFED6266)   // aurora warm mid
val Magenta500 = Color(0xFFE8249D) // aurora vivid mid
val Violet500 = Color(0xFF6D59DB)  // aurora cool end / accent

val White = Color(0xFFFFFFFF)
val Lavender300 = Color(0xFFB4ACEC)
val Lavender500 = Color(0xFF9C91AC)

val GreenCredit = Color(0xFF3DDC97)
val RedDebit = Color(0xFFFF7A8A)
val LiveLocation = Color(0xFF4FC3F7)   // sky-cyan — the live "you are here" GPS puck, OFF the aurora ramp so it never reads as a spend pin

// ── Semantic ──
val BgColor = Indigo900
val SurfaceColor = Indigo800
val SurfaceRaised = Indigo700
val BorderColor = Color(0x1FFFFFFF)       // white @ 12%
val HairlineColor = Color(0x12FFFFFF)      // white @ 7% (subtle dividers)
val TextPrimary = White
val TextSecondary = Lavender300
val TextTertiary = Lavender500
val AccentColor = Violet500
val IconAccent = Color(0xFFB9AEF5)   // lightened violet for line-icons on tinted tiles
val WarnColor = Amber500

// ── Input/field tokens ──
val FieldBg = Color(0x0DFFFFFF)            // white @ 5%
val FieldBorder = Color(0x24FFFFFF)        // white @ 14%
val FieldBorderFocus = Violet500

// ─────────────────────────────────────────────────────────────────────────────
// Aurora — the one signature gradient. Reserved for the primary CTA / brand mark /
// active accent (NOT flat surfaces). Default start=Zero, end=Infinite → top-left→
// bottom-right diagonal, approximating the token's 125° angle; size-relative, so a
// single instance reuses across composables of any size.
// ─────────────────────────────────────────────────────────────────────────────
val AuroraColorStops = arrayOf(
    0.0f to Amber500,
    0.32f to Coral500,
    0.62f to Magenta500,
    1.0f to Violet500,
)
val AuroraBrush: Brush = Brush.linearGradient(colorStops = AuroraColorStops)

/**
 * Sample the aurora gradient at fraction t∈[0,1] → the hue at that position along the ramp
 * (amber→coral→magenta→violet). Shared by the Insights chart (curve hue) and the map (pin recency hue)
 * so both speak the same color language. Pure — clamps t and lerps between the bracketing stops.
 */
fun auroraAt(t: Float): Color {
    val ct = t.coerceIn(0f, 1f)
    var lo = AuroraColorStops.first()
    var hi = AuroraColorStops.last()
    for (i in 0 until AuroraColorStops.size - 1) {
        val a = AuroraColorStops[i]
        val b = AuroraColorStops[i + 1]
        if (ct >= a.first && ct <= b.first) { lo = a; hi = b; break }
    }
    val seg = hi.first - lo.first
    val local = if (seg <= 0f) 0f else (ct - lo.first) / seg
    return lerp(lo.second, hi.second, local)
}
