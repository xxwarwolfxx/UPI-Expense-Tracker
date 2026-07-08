package com.goushik.upiwallet.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goushik.upiwallet.util.Permissions

/** Live state of the validated capture permission set. */
data class CaptureGrants(val a11y: Boolean, val sms: Boolean, val battery: Boolean) {
    val allRequired: Boolean get() = a11y && sms   // battery is recommended, not required
}

/**
 * Reads a11y/SMS/battery grant state and **re-reads on every ON_RESUME** — the user leaves to
 * Settings to grant, and we detect it on return. Returns the grants + a manual `refresh()` to call
 * after an in-app request (e.g. the SMS permission dialog) lands.
 */
@Composable
fun rememberCaptureGrants(): Pair<CaptureGrants, () -> Unit> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var key by remember { mutableStateOf(0) }
    val grants = remember(key) {
        CaptureGrants(
            a11y = Permissions.isA11yEnabled(ctx),
            sms = Permissions.isSmsGranted(ctx),
            battery = Permissions.isBatteryUnrestricted(ctx),
        )
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) key++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return grants to { key++ }
}
