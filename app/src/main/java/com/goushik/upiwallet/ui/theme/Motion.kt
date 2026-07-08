package com.goushik.upiwallet.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// Motion set from tokens.json §motion. Durations in ms.
object Motion {
    const val FAST = 120
    const val BASE = 220
    const val SLOW = 360
    val EasingStandard = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1.0f)
}

/** True when the OS "remove animations" / reduce-motion setting is on (ANIMATOR_DURATION_SCALE == 0).
 *  The same gate [com.goushik.upiwallet.ui.home.rememberDeviceTilt] uses — honour it for transitions too. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
