package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.goushik.upiwallet.ui.theme.Amber500
import com.goushik.upiwallet.ui.theme.Coral500
import com.goushik.upiwallet.ui.theme.Indigo1000
import com.goushik.upiwallet.ui.theme.Indigo800
import com.goushik.upiwallet.ui.theme.Indigo950
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.Violet500

/**
 * The Home "rich glass" canvas (home-v1.html `.aurora-bg`): a top-lit indigo radial base with four
 * soft aurora glow blobs. This is the layer the frosted-glass surfaces blur via Haze — mark it with
 * `Modifier.hazeSource(...)` at the call site. Blobs are radial-gradient rects (color → transparent),
 * not blurred circles, so the falloff is edge-free and cheap (no extra blur layer).
 */
@Composable
fun AuroraGlassBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // base: bright-ish indigo at top-center fading to the deep scrim at the corners
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(0f to Indigo800, 0.6f to Indigo950, 1f to Indigo1000),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension,
            ),
        )
        blob(Magenta500, 0.50f, Offset(size.width * 1.00f, size.height * 0.02f), size.width * 0.62f)
        blob(Violet500, 0.42f, Offset(size.width * 0.00f, size.height * 0.40f), size.width * 0.55f)
        blob(Amber500, 0.28f, Offset(size.width * 1.00f, size.height * 0.78f), size.width * 0.52f)
        blob(Coral500, 0.26f, Offset(size.width * 0.15f, size.height * 1.00f), size.width * 0.52f)
    }
}

private fun DrawScope.blob(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
    )
}
