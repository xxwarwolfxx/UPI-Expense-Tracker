package com.goushik.upiwallet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 4pt space scale from tokens.json §space (s1..s8 = 4,8,12,16,20,24,32,40).
@Immutable
data class Spacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s7: Dp = 32.dp,
    val s8: Dp = 40.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
