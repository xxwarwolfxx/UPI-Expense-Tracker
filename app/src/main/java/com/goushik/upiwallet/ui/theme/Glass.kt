package com.goushik.upiwallet.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

// ─────────────────────────────────────────────────────────────────────────────
// Glass — Phase 3. Mirrors tokens.json §glass (alpha-on-white). Surfaces are neutral
// frosted glass over the aurora wash; the real backdrop *blur* (Haze) is a separate
// follow-on layered in after the no-dep Home verifies on device.
// ─────────────────────────────────────────────────────────────────────────────
val GlassSurface = Color(0x0FFFFFFF)        // white @ 6%
val GlassSurfaceStrong = Color(0x1AFFFFFF)  // white @ 10%
val GlassBorder = BorderColor               // white @ 12% — the shared hairline
val GlassSpecular = Color(0xFFFFFFFF)       // glass.specular — the moving shine's bright core (alpha set at draw)

/**
 * Shared [HazeState] for the Home, provided by `AppShell`. When present, [glassSurface] blurs the
 * aurora background behind it; when null (onboarding/status/previews) surfaces fall back to a plain
 * translucent fill. NOTE: only set `blur = true` for surfaces drawn directly over the background —
 * a surface nested on another surface (e.g. chips on the hero) must keep the plain fill, since Haze
 * samples the root background, not its immediate parent.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** Neutral frosted-glass surface: (optional) backdrop blur of the aurora + a translucent white fill
 *  + hairline border, clipped to [shape]. */
@Composable
fun Modifier.glassSurface(shape: Shape, strong: Boolean = false, blur: Boolean = true): Modifier {
    val haze = LocalHazeState.current
    val fill = if (strong) GlassSurfaceStrong else GlassSurface
    return this
        .clip(shape)
        .then(
            if (blur && haze != null) {
                Modifier.hazeEffect(haze) {
                    blurRadius = 22.dp
                    backgroundColor = BgColor
                    noiseFactor = 0f
                }
            } else {
                Modifier
            },
        )
        .background(fill)
        .border(1.dp, GlassBorder, shape)
}

/**
 * A *raised* glass surface — [glassSurface] with depth: the same backdrop blur, but a subtle top-bright
 * vertical gradient fill and a soft outer drop shadow so the surface reads as its own plane lifted off the
 * aurora (the "Refined Depth" home pass). Used for the recent-transaction list cards + the home stat tiles.
 */
@Composable
fun Modifier.raisedGlass(shape: Shape, elevation: Dp = 16.dp): Modifier {
    val haze = LocalHazeState.current
    return this
        .shadow(elevation, shape, clip = false)
        .clip(shape)
        .then(
            if (haze != null) {
                Modifier.hazeEffect(haze) {
                    blurRadius = 22.dp
                    backgroundColor = BgColor
                    noiseFactor = 0f
                }
            } else {
                Modifier
            },
        )
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.035f)),
            ),
        )
        .border(1.dp, GlassBorder, shape)
}

/**
 * The hero card surface (hero-options.html → "solid + aurora sheen", his pick): an **opaque** deep-indigo
 * base with a faint full-card aurora wash so the solid surface carries brand iridescence. The base stays
 * dark enough that the white balance number reads cleanly. The *moving* parts — the parallaxing corner
 * glow and the iridescent specular band — are drawn per-frame on top of this in [specularShine], so they
 * stay out of this size-cached block. The static base/sheen only rebuild on a size change.
 */
fun Modifier.heroCardBackground(shape: Shape): Modifier = this
    .clip(shape)
    .drawWithCache {
        val base = Brush.linearGradient(
            colors = listOf(Indigo700, Indigo800),   // opaque — the card is a solid surface now
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),    // ~160° diagonal
        )
        // Aurora sheen — the signature gradient at a low alpha; edge-warm at top-left, cool at bottom-right.
        val sheen = Brush.linearGradient(
            colorStops = AuroraColorStops.map { it.first to it.second.copy(alpha = 0.10f) }.toTypedArray(),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        )
        onDrawBehind {
            drawRect(base)
            drawRect(sheen)
        }
    }
    .border(1.dp, GlassBorder, shape)

/**
 * glass.corner-glow token, CSS → Compose:
 *   radial-gradient(120% 95% at 100% -10%, rgba(232,36,157,.40), rgba(246,160,55,.16) 32%, transparent 60%)
 * Compose radial gradients are circular, so the elliptical reach is approximated with radius ≈ 1.2×
 * width (tune on device). Defaults to the card's top-right, slightly above the edge; [center] is overridden
 * by [specularShine] to parallax the glow with device tilt so the aurora "catches the light" as it moves.
 */
fun cornerGlowBrush(size: Size, center: Offset = Offset(size.width, -size.height * 0.10f)): Brush =
    Brush.radialGradient(
        colorStops = arrayOf(
            0.00f to Magenta500.copy(alpha = 0.40f),
            0.32f to Amber500.copy(alpha = 0.16f),
            0.60f to Color.Transparent,
        ),
        center = center,
        radius = (size.width * 1.2f).coerceAtLeast(1f),
    )
