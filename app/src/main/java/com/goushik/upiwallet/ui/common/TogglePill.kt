package com.goushik.upiwallet.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.ui.theme.FieldBorder
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.White

/**
 * A glassmorphic on/off switch in the app's design language: a glass track that fills with the aurora
 * gradient when on, and a white thumb that slides across. [onToggle] receives the new state. One shared
 * control across the wallet-mode, reminder and location settings (replaced the old "On/Off" text pill).
 */
@Composable
fun TogglePill(on: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val trackW = 46.dp
    val trackH = 28.dp
    val thumb = 22.dp
    val inset = 3.dp
    val thumbX by animateDpAsState(if (on) trackW - thumb - inset else inset, label = "thumb")
    Box(
        modifier
            .size(trackW, trackH)
            .clip(PillShape)
            .then(
                if (on) Modifier.background(AuroraBrush)
                else Modifier.background(FieldBg).border(1.dp, FieldBorder, PillShape),
            )
            .clickable { onToggle(!on) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = thumbX).size(thumb)
                .shadow(2.dp, PillShape).clip(PillShape).background(White),
        )
    }
}
