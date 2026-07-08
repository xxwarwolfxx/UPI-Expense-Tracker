package com.goushik.upiwallet.ui.donate

import android.content.ActivityNotFoundException
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.R
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.theme.Motion
import com.goushik.upiwallet.ui.theme.rememberReduceMotion
import com.goushik.upiwallet.util.Upi
import kotlinx.coroutines.delay

// All three layers share one 228×155 native pixel-grid (each element in its final position, tight-cropped to
// a common bbox so the frame fills the width → 215×143), so they register at 0,0 and overlay cleanly at
// fillMaxWidth().aspectRatio(ASPECT). Crisp-rastered from the source SVGs via resvg; Android nearest-upscales
// them — FilterQuality.None.
private const val DONATE_ART_ASPECT = 215f / 143f

// The DONATE plaque's opaque bounds within the canvas, as fractions — drives the recess, the press sink,
// and the click target so ONLY the plaque is interactive. (Measured from donate_plaque.png alpha bbox.)
private const val PLAQUE_L = 13f / 215f
private const val PLAQUE_T = 77f / 143f
private const val PLAQUE_R = 202f / 215f
private const val PLAQUE_B = 129f / 143f

/**
 * Phase 3 — the pixelated-you donate button. Only the **inner DONATE plaque** is the button: at rest it sits
 * raised, showing a hard dark shadow along the **bottom** (a recess behind it); on press it **sinks down
 * flush** so the bottom shadow closes (+ a haptic tick). The person + outer frame stay put. Layers, back→front:
 *
 *   1. recess  — a dark rounded-rect behind the plaque; the raised board reveals it as the bottom shadow.
 *   2. plaque  — `donate_plaque` (the button face); rest = raised, press = sunk flush.
 *   3. scene   — `donate_scene_{hat,wave}` on top (frame + person + hands); the hat alternates per tip.
 *   4. a transparent click target at the plaque's bounds → tapping the person does nothing.
 *
 * Tapping goes STRAIGHT to the Razorpay-hosted donate page in the browser — every direct-UPI payee
 * tested on 2026-07-03 was dead on the receiving side (see [Upi]'s KDoc for the full graveyard); the
 * hosted page is the one flow verified to open end-to-end. No intermediate sheet.
 *
 * Pixel art is drawn with the **bitmap** Image overload + `FilterQuality.None` (the painter overload has no
 * filterQuality; default bilinear would blur the pixels).
 */
@Composable
fun DonateButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = LocalView.current
    // Donation COUNT (not a one-way flag): odd/even flips the hat so it alternates on every tip
    // (tip → hat off/waving, tip again → hat back on, back and forth).
    val donationCount by ServiceLocator.uiPrefs.donationCount.collectAsStateWithLifecycle()
    val hasDonated = donationCount > 0
    val hatOff = donationCount % 2 == 1
    val reduceMotion = rememberReduceMotion()

    // Returning from the browser flow is our "donated" signal (optimistic; a forgeable cosmetic
    // counter, fine for a free sideload — payment results aren't observable from here).
    val payLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        ServiceLocator.uiPrefs.incrementDonations()
    }

    // ── press: the board sits raised (hard shadow at the bottom) and sinks flush on press (shadow closes) +
    // a haptic. Driven by a tap-gesture detector (below), NOT interactionSource — in a LazyColumn
    // collectIsPressedAsState delays the press ~100ms (so it was lost on a quick/light tap). ──
    var isPressed by remember { mutableStateOf(false) }
    val lift = 9.dp
    val plaqueY by animateDpAsState(
        targetValue = if (isPressed) 0.dp else -lift, // rest = raised (shadow below); press = sunk flush
        animationSpec = tween(if (reduceMotion) 0 else Motion.FAST, easing = Motion.EasingStandard),
        label = "plaqueSink",
    )

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(DONATE_ART_ASPECT)) {
        val w = maxWidth
        val h = maxHeight
        val plaqueLeft = w * PLAQUE_L
        val plaqueTop = h * PLAQUE_T
        val plaqueW = w * (PLAQUE_R - PLAQUE_L)
        val plaqueH = h * (PLAQUE_B - PLAQUE_T)

        // 1) recess — dark slot behind the plaque. With the board raised at rest, its lower strip shows as a
        //    hard shadow at the BOTTOM; the board sinking on press covers it. (Scene on top clips the edges.)
        Box(
            Modifier
                .offset(plaqueLeft, plaqueTop)
                .size(plaqueW, plaqueH)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xC00A0712)),
        )

        // 2) plaque (the button) — full-canvas image, plaque already in position; sinks on press
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.donate_plaque),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize().offset { IntOffset(0, plaqueY.roundToPx()) },
        )

        // 3) scene (person + outer frame + hands) on top — hands stay put over the sinking plaque. The hat
        //    ALTERNATES on every tip (odd = off/waving, even = back on); a crossfade eases the swap.
        Crossfade(
            targetState = hatOff,
            animationSpec = tween(if (reduceMotion) 0 else Motion.BASE, easing = Motion.EasingStandard),
            label = "hatSwap",
        ) { off ->
            Image(
                bitmap = ImageBitmap.imageResource(
                    if (off) R.drawable.donate_scene_wave else R.drawable.donate_scene_hat,
                ),
                contentDescription = if (hasDonated) "You donated — thank you!" else "Donate to the developer",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 4) click target = plaque bounds only → only the plaque is the button. detectTapGestures.onPress
        //    fires the instant the finger lands (no scroll-delay), so a LIGHT/QUICK tap sinks + haptics too.
        Box(
            Modifier
                .offset(plaqueLeft, plaqueTop)
                .size(plaqueW, plaqueH)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            tryAwaitRelease()
                            delay(90) // keep the plaque sunk briefly so an instant tap still visibly goes in
                            isPressed = false
                        },
                        onTap = {
                            try {
                                payLauncher.launch(Upi.donatePageIntent())
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, "No browser found to open the donate page.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                },
        )
    }

}
