package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.WarnColor

/**
 * The concrete "capture paused" banner — shown wherever capture is paused, by the same definition the
 * widgets and the reminder use ([com.goushik.upiwallet.domain.CaptureWatch.isPaused], via
 * [CaptureGrants.capturePaused]). Tapping it routes to Accessibility settings via [onFix]. Shared by Home
 * and Settings.
 */
@Composable
fun CaptureDownBanner(onFix: () -> Unit, modifier: Modifier = Modifier, stuck: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WarnColor.copy(alpha = 0.10f))
            .border(1.dp, WarnColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(onClick = onFix)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(WarnColor))
        Spacer(Modifier.width(11.dp))
        Text(
            if (stuck) "Capture has stopped — switch it off and on again"
            else "Capture is paused — Accessibility is off",
            style = MaterialTheme.typography.bodyMedium, color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("Fix →", style = MaterialTheme.typography.labelLarge, color = WarnColor)
    }
}
