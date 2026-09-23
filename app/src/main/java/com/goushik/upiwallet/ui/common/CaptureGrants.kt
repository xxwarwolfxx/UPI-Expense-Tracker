package com.goushik.upiwallet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.CaptureWatch
import com.goushik.upiwallet.util.Permissions
import kotlinx.coroutines.launch

/**
 * Live state of the validated capture permission set.
 *
 * [a11y] is the raw grant ("has the user switched our service on?") — what onboarding steps on.
 * [capturePaused] is [CaptureWatch.isPaused], the one definition of "payments aren't being recorded" that the
 * widgets and the reminder also use; the Home banner and the Settings "Accessibility (capture)" row read it,
 * so the app never says "Healthy" while a widget says "Capture paused".
 */
data class CaptureGrants(
    val a11y: Boolean,
    val sms: Boolean,
    val battery: Boolean,
    val capturePaused: Boolean = !a11y,
    /** Paused while Settings still shows the switch on — the banner then says "switch it off and on". */
    val captureStuck: Boolean = false,
) {
    val allRequired: Boolean get() = a11y && sms   // battery is recommended, not required
}

/**
 * Reads a11y/SMS/battery grant state and **re-reads on every ON_RESUME** — the user leaves to
 * Settings to grant, and we detect it on return — and whenever [CaptureWatch.changes] moves (the service
 * connecting or unbinding, or any capture-watch tick), so a switch-off while the app is open shows at once.
 * Returns the grants + a manual `refresh()` to call after an in-app request (e.g. the SMS permission
 * dialog) lands.
 */
@Composable
fun rememberCaptureGrants(): Pair<CaptureGrants, () -> Unit> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var key by remember { mutableStateOf(0) }
    val live by CaptureWatch.changes.collectAsState()
    val grants = remember(key, live) {
        CaptureGrants(
            a11y = Permissions.isA11yEnabled(ctx),
            sms = Permissions.isSmsGranted(ctx),
            battery = Permissions.isBatteryUnrestricted(ctx),
            capturePaused = CaptureWatch.isPaused(ctx),
            captureStuck = CaptureWatch.isStuck(ctx),
        )
    }
    DisposableEffect(owner) {
        val app = ctx.applicationContext
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                key++
                // Every resume is also a capture-watch tick: coming back from Settings with the switch
                // flipped repaints the widgets and posts/clears the "capture paused" reminder right away.
                // Ticking here (not on every re-read) means one tick per resume: adding the observer to an
                // already-resumed screen replays ON_RESUME once, so there is no extra first-composition
                // tick; and a re-read caused by a tick never ticks again.
                ServiceLocator.appScope.launch { CaptureWatch.tick(app) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return grants to { key++ }
}
