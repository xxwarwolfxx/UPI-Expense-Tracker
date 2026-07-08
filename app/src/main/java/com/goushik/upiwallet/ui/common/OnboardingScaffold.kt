package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.theme.AccentColor
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextTertiary

/** Progress bar for the 3-stage setup (Permissions · Balance · You). Aurora = current. */
@Composable
fun StepDots(index: Int, count: Int, label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until count) {
            val base = Modifier.weight(1f).height(5.dp).clip(PillShape)
            if (i == index) {
                Box(base.background(AuroraBrush))
            } else {
                Box(base.background(if (i < index) AccentColor else Color(0x29FFFFFF)))
            }
            if (i < count - 1) Spacer(Modifier.width(7.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0DFFFFFF))
            .border(1.dp, HairlineColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { IconChevronLeft(TextPrimary) }
}

/**
 * Frame for a stepped onboarding screen: flat-dark background, optional back button + top slot
 * (e.g. [StepDots]), a scrollable content region, and a pinned footer (the CTA).
 */
@Composable
fun OnboardingScaffold(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    top: (@Composable ColumnScope.() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    WalletBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            if (onBack != null) {
                BackButton(onBack)
                Spacer(Modifier.height(12.dp))
            }
            if (top != null) {
                top()
                Spacer(Modifier.height(22.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) { content() }
            Spacer(Modifier.height(12.dp))
            footer()
            Spacer(Modifier.height(16.dp))
        }
    }
}
