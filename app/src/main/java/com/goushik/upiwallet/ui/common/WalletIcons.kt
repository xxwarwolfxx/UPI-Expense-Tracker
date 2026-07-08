package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Lightweight Canvas line-icons (avoids the material-icons-extended dependency).
// All drawn on a normalized square; stroke scales with size.

private fun DrawScope.sw(frac: Float = 0.09f) = size.minDimension * frac

@Composable
fun IconChevronLeft(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.11f)
        drawLine(tint, Offset(w * 0.62f, h * 0.24f), Offset(w * 0.36f, h * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.36f, h * 0.5f), Offset(w * 0.62f, h * 0.76f), s, StrokeCap.Round)
    }
}

@Composable
fun IconChevronDown(tint: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.11f)
        drawLine(tint, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.64f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.64f), Offset(w * 0.72f, h * 0.42f), s, StrokeCap.Round)
    }
}

@Composable
fun IconCheck(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.14f)
        drawLine(tint, Offset(w * 0.2f, h * 0.52f), Offset(w * 0.42f, h * 0.74f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.42f, h * 0.74f), Offset(w * 0.8f, h * 0.3f), s, StrokeCap.Round)
    }
}

@Composable
fun IconLock(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // body
        drawRoundRect(
            tint, topLeft = Offset(w * 0.24f, h * 0.46f), size = Size(w * 0.52f, h * 0.36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 2, s * 2), style = Stroke(s),
        )
        // shackle
        drawArc(
            tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.34f, h * 0.24f), size = Size(w * 0.32f, h * 0.32f), style = Stroke(s),
        )
    }
}

@Composable
fun IconAccessibility(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.2f))
        // arms (horizontal)
        drawLine(tint, Offset(w * 0.22f, h * 0.36f), Offset(w * 0.78f, h * 0.36f), s, StrokeCap.Round)
        // torso
        drawLine(tint, Offset(w * 0.5f, h * 0.3f), Offset(w * 0.5f, h * 0.6f), s, StrokeCap.Round)
        // legs
        drawLine(tint, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.34f, h * 0.84f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.66f, h * 0.84f), s, StrokeCap.Round)
    }
}

@Composable
fun IconSms(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        val p = Path().apply {
            moveTo(w * 0.2f, h * 0.24f)
            lineTo(w * 0.8f, h * 0.24f)
            lineTo(w * 0.8f, h * 0.62f)
            lineTo(w * 0.42f, h * 0.62f)
            lineTo(w * 0.26f, h * 0.78f)
            lineTo(w * 0.26f, h * 0.62f)
            lineTo(w * 0.2f, h * 0.62f)
            close()
        }
        drawPath(p, tint, style = Stroke(s))
    }
}

@Composable
fun IconBattery(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.08f)
        drawRoundRect(
            tint, topLeft = Offset(w * 0.16f, h * 0.34f), size = Size(w * 0.6f, h * 0.32f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 1.5f, s * 1.5f), style = Stroke(s),
        )
        drawLine(tint, Offset(w * 0.82f, h * 0.44f), Offset(w * 0.82f, h * 0.56f), s, StrokeCap.Round)
        // bolt
        val bolt = Path().apply {
            moveTo(w * 0.46f, h * 0.38f); lineTo(w * 0.36f, h * 0.52f)
            lineTo(w * 0.46f, h * 0.52f); lineTo(w * 0.4f, h * 0.62f)
        }
        drawPath(bolt, tint, style = Stroke(s, cap = StrokeCap.Round))
    }
}

@Composable
fun IconInfo(tint: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.1f)
        drawCircle(tint, radius = w * 0.42f, center = Offset(w / 2, h / 2), style = Stroke(s))
        drawLine(tint, Offset(w / 2, h * 0.46f), Offset(w / 2, h * 0.7f), s, StrokeCap.Round)
        drawCircle(tint, radius = s * 0.6f, center = Offset(w / 2, h * 0.32f))
    }
}

// ── Bottom-nav + home glyphs (Phase 3). Same Canvas-line style as above. ──

@Composable
fun IconHome(tint: Color, modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // roof
        drawLine(tint, Offset(w * 0.13f, h * 0.46f), Offset(w * 0.5f, h * 0.16f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.87f, h * 0.46f), s, StrokeCap.Round)
        // body (open at top)
        val body = Path().apply {
            moveTo(w * 0.23f, h * 0.4f)
            lineTo(w * 0.23f, h * 0.84f)
            lineTo(w * 0.77f, h * 0.84f)
            lineTo(w * 0.77f, h * 0.4f)
        }
        drawPath(body, tint, style = Stroke(s, join = StrokeJoin.Round))
    }
}

@Composable
fun IconInsights(tint: Color, modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // L-axis
        drawLine(tint, Offset(w * 0.17f, h * 0.2f), Offset(w * 0.17f, h * 0.8f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.17f, h * 0.8f), Offset(w * 0.84f, h * 0.8f), s, StrokeCap.Round)
        // bars
        drawLine(tint, Offset(w * 0.36f, h * 0.66f), Offset(w * 0.36f, h * 0.46f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.52f, h * 0.66f), Offset(w * 0.52f, h * 0.34f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.68f, h * 0.66f), Offset(w * 0.68f, h * 0.54f), s, StrokeCap.Round)
    }
}

@Composable
fun IconPlus(tint: Color, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.1f)
        drawLine(tint, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.5f, h * 0.78f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), s, StrokeCap.Round)
    }
}

@Composable
fun IconReview(tint: Color, modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // clipboard
        drawRoundRect(
            tint, topLeft = Offset(w * 0.2f, h * 0.18f), size = Size(w * 0.6f, h * 0.66f),
            cornerRadius = CornerRadius(s * 1.6f, s * 1.6f), style = Stroke(s),
        )
        // check
        drawLine(tint, Offset(w * 0.34f, h * 0.52f), Offset(w * 0.46f, h * 0.64f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.46f, h * 0.64f), Offset(w * 0.7f, h * 0.36f), s, StrokeCap.Round)
    }
}

@Composable
fun IconSettings(tint: Color, modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // two slider tracks + knobs
        drawLine(tint, Offset(w * 0.16f, h * 0.34f), Offset(w * 0.84f, h * 0.34f), s, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.1f, center = Offset(w * 0.62f, h * 0.34f))
        drawLine(tint, Offset(w * 0.16f, h * 0.66f), Offset(w * 0.84f, h * 0.66f), s, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.1f, center = Offset(w * 0.36f, h * 0.66f))
    }
}

@Composable
fun IconBell(tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // dome (top semicircle): endpoints land at y=0.40 where the flares begin
        drawArc(
            tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.16f), size = Size(w * 0.44f, h * 0.48f),
            style = Stroke(s, cap = StrokeCap.Round),
        )
        // flares out to the wider rim
        drawLine(tint, Offset(w * 0.28f, h * 0.40f), Offset(w * 0.2f, h * 0.66f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.72f, h * 0.40f), Offset(w * 0.8f, h * 0.66f), s, StrokeCap.Round)
        // rim
        drawLine(tint, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.8f, h * 0.66f), s, StrokeCap.Round)
        // clapper
        drawArc(
            tint, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(w * 0.42f, h * 0.68f), size = Size(w * 0.16f, h * 0.12f),
            style = Stroke(s, cap = StrokeCap.Round),
        )
    }
}

/** Neutral transaction glyph (no category yet): a two-way transfer arrow. Direction is carried by
 *  the row's tint + signed amount, so this stays category-agnostic. */
@Composable
fun IconTxnGeneric(tint: Color, modifier: Modifier = Modifier, size: Dp = 19.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        // top arrow → right
        drawLine(tint, Offset(w * 0.26f, h * 0.4f), Offset(w * 0.74f, h * 0.4f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.62f, h * 0.3f), Offset(w * 0.74f, h * 0.4f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.62f, h * 0.5f), Offset(w * 0.74f, h * 0.4f), s, StrokeCap.Round)
        // bottom arrow ← left
        drawLine(tint, Offset(w * 0.74f, h * 0.6f), Offset(w * 0.26f, h * 0.6f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, h * 0.5f), Offset(w * 0.26f, h * 0.6f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, h * 0.7f), Offset(w * 0.26f, h * 0.6f), s, StrokeCap.Round)
    }
}

/** Money out — a single right-arrow → for a spend (debit) row. */
@Composable
fun IconArrowOut(tint: Color, modifier: Modifier = Modifier, size: Dp = 19.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        drawLine(tint, Offset(w * 0.26f, h * 0.5f), Offset(w * 0.74f, h * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.60f, h * 0.36f), Offset(w * 0.74f, h * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.60f, h * 0.64f), Offset(w * 0.74f, h * 0.5f), s, StrokeCap.Round)
    }
}

/** Money in — a single left-arrow ← for a credit (received) row. */
@Composable
fun IconArrowIn(tint: Color, modifier: Modifier = Modifier, size: Dp = 19.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.085f)
        drawLine(tint, Offset(w * 0.74f, h * 0.5f), Offset(w * 0.26f, h * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.40f, h * 0.36f), Offset(w * 0.26f, h * 0.5f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.40f, h * 0.64f), Offset(w * 0.26f, h * 0.5f), s, StrokeCap.Round)
    }
}

/** A person — for a P2P payment (to/from an individual). Head + shoulders. */
@Composable
fun IconPerson(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.078f)
        drawCircle(tint, radius = w * 0.155f, center = Offset(w * 0.5f, h * 0.32f), style = Stroke(s))
        drawArc(
            tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.55f), size = Size(w * 0.64f, h * 0.52f),
            style = Stroke(s, cap = StrokeCap.Round),
        )
    }
}

/** A business / merchant — a briefcase. Used for any non-person payee (merchants, finance, self-bank). */
@Composable
fun IconBusiness(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.078f)
        // handle
        val handle = Path().apply {
            moveTo(w * 0.40f, h * 0.40f); lineTo(w * 0.40f, h * 0.33f)
            lineTo(w * 0.60f, h * 0.33f); lineTo(w * 0.60f, h * 0.40f)
        }
        drawPath(handle, tint, style = Stroke(s, join = StrokeJoin.Round, cap = StrokeCap.Round))
        // body
        drawRoundRect(
            tint, topLeft = Offset(w * 0.18f, h * 0.40f), size = Size(w * 0.64f, h * 0.38f),
            cornerRadius = CornerRadius(s * 1.8f, s * 1.8f), style = Stroke(s),
        )
        // clasp band
        drawLine(tint, Offset(w * 0.18f, h * 0.55f), Offset(w * 0.82f, h * 0.55f), s, StrokeCap.Round)
    }
}

/** A flip / swap glyph (two opposed arrows) — flips the spend-only Home hero between its spend face and
 *  its balance face. */
@Composable
fun IconFlip(tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.095f)
        // top arrow → (points right)
        drawLine(tint, Offset(w * 0.20f, h * 0.36f), Offset(w * 0.82f, h * 0.36f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.66f, h * 0.22f), Offset(w * 0.83f, h * 0.36f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.66f, h * 0.50f), Offset(w * 0.83f, h * 0.36f), s, StrokeCap.Round)
        // bottom arrow ← (points left)
        drawLine(tint, Offset(w * 0.18f, h * 0.64f), Offset(w * 0.80f, h * 0.64f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.34f, h * 0.50f), Offset(w * 0.17f, h * 0.64f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.34f, h * 0.78f), Offset(w * 0.17f, h * 0.64f), s, StrokeCap.Round)
    }
}

/** A payment card — the "My balance too" choice in the onboarding mode step (Phase C). */
@Composable
fun IconWallet(tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = sw(0.078f)
        drawRoundRect(
            tint, topLeft = Offset(w * 0.14f, h * 0.26f), size = Size(w * 0.72f, h * 0.50f),
            cornerRadius = CornerRadius(s * 2f, s * 2f), style = Stroke(s),
        )
        drawLine(tint, Offset(w * 0.14f, h * 0.44f), Offset(w * 0.86f, h * 0.44f), s)
        drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.68f, h * 0.62f))
    }
}