package com.goushik.upiwallet.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark-only scheme mapped from the semantic tokens. Dynamic color is intentionally
// OFF — this app owns its palette (see DESIGN-SYSTEM.md §5: dark-mode only for v1).
private val WalletColorScheme = darkColorScheme(
    primary = Violet500,
    onPrimary = White,
    secondary = Magenta500,
    onSecondary = White,
    tertiary = Amber500,
    background = BgColor,
    onBackground = TextPrimary,
    surface = SurfaceColor,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    outline = BorderColor,
    outlineVariant = HairlineColor,
    error = RedDebit,
    onError = White,
)

@Composable
fun UPIWalletTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Dark surfaces → light (white) status-bar icons.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = WalletColorScheme,
            typography = WalletTypography,
            shapes = WalletShapes,
            content = content,
        )
    }
}
