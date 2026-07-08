package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.White

/** The one aurora moment in the flow: the primary call-to-action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingArrow: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.shadow(14.dp, PillShape, spotColor = Magenta500, ambientColor = Magenta500)
                else Modifier,
            )
            .clip(PillShape)
            .background(if (enabled) AuroraBrush else SolidColor(Color(0x12FFFFFF)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) White else TextTertiary,
        )
        if (trailingArrow && enabled) {
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val h = size.height
                val sw = 2.2.dp.toPx()
                drawLine(White, Offset(w * 0.12f, h / 2f), Offset(w * 0.82f, h / 2f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(White, Offset(w * 0.55f, h * 0.26f), Offset(w * 0.84f, h / 2f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(White, Offset(w * 0.55f, h * 0.74f), Offset(w * 0.84f, h / 2f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

/** Secondary, low-emphasis action — flat with a hairline border. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .border(1.dp, Color(0x1FFFFFFF), PillShape)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
    }
}
