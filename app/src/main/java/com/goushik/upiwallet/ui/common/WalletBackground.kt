package com.goushik.upiwallet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.theme.BgColor
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.Violet500

/**
 * The flat-dark canvas every onboarding/status screen sits on: deep-indigo base + a
 * soft aurora wash at the top for brand warmth. NO glass/blur — that's reserved for
 * the Phase-3 Home hero.
 */
@Composable
fun WalletBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(BgColor)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Magenta500.copy(alpha = 0.13f),
                            Violet500.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        content()
    }
}
