package com.goushik.upiwallet.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.common.IconHome
import com.goushik.upiwallet.ui.common.IconInsights
import com.goushik.upiwallet.ui.common.IconPlus
import com.goushik.upiwallet.ui.common.IconReview
import com.goushik.upiwallet.ui.common.IconSettings
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.BgColor
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.Indigo950
import com.goushik.upiwallet.ui.theme.LocalHazeState
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.White
import dev.chrisbanes.haze.hazeEffect

/** Approx content height of the bar (excludes the system nav-bar inset). Screens pad by this so
 *  their scrollable content clears the translucent overlay. */
val BottomNavHeight = 64.dp

/**
 * The persistent bottom navigation — a translucent overlay (not a Material NavigationBar), matching
 * home-v1.html `.nav`: vertical gradient fade up from the bottom + a hairline top, four tabs around a
 * raised aurora "+" (the second reserved aurora moment). Lives at the shell level so it shows on
 * every tab and highlights the current one.
 */
@Composable
fun BottomNavBar(
    current: Route,
    hasReview: Boolean,
    onSelect: (Route) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haze = LocalHazeState.current
    Box(
        modifier
            .fillMaxWidth()
            // Absorb taps anywhere on the bar so a click in the GAPS between tabs doesn't fall through to
            // whatever sits behind this translucent overlay (e.g. a row/button on the screen underneath).
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .then(
                if (haze != null) {
                    Modifier.hazeEffect(haze) {
                        blurRadius = 20.dp
                        backgroundColor = BgColor
                        noiseFactor = 0f
                    }
                } else {
                    Modifier
                },
            )
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.45f to Indigo950.copy(alpha = 0.40f),
                        1.0f to Indigo950.copy(alpha = 0.92f),
                    ),
                ),
            ),
    ) {
        // hairline top edge
        Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor).align(Alignment.TopCenter))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            NavTab("Home", current == Route.Home, onClick = { onSelect(Route.Home) }) { IconHome(it) }
            NavTab("Insights", current == Route.Insights, onClick = { onSelect(Route.Insights) }) { IconInsights(it) }
            AddButton(onAdd)
            NavTab("Review", current == Route.Review, badge = hasReview, onClick = { onSelect(Route.Review) }) { IconReview(it) }
            NavTab("Settings", current == Route.Settings, onClick = { onSelect(Route.Settings) }) { IconSettings(it) }
        }
    }
}

@Composable
private fun NavTab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    badge: Boolean = false,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (active) White else TextTertiary
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            icon(tint)
            if (badge) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(RedDebit),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun AddButton(onAdd: () -> Unit) {
    // Raised above the bar (mockup's margin-top:-18) without consuming layout height.
    Box(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -14.dp.roundToPx()) }
            }
            .size(56.dp)
            .shadow(16.dp, RoundedCornerShape(18.dp), spotColor = Magenta500, ambientColor = Magenta500)
            .clip(RoundedCornerShape(18.dp))
            .background(AuroraBrush)
            .clickable(onClick = onAdd),
        contentAlignment = Alignment.Center,
    ) {
        IconPlus(White, size = 26.dp)
    }
}
