package com.goushik.upiwallet.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goushik.upiwallet.ui.theme.GlassSpecular
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.auroraAt
import com.goushik.upiwallet.ui.theme.cornerGlowBrush
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Device-tilt — the hero balance card as a physical card in 3D space. The phone's
// motion sensor drives a subtle 3D slant + a moving aurora shine. Read the tilt
// state ONLY inside the graphicsLayer{}/drawBehind{} lambdas below — never in a
// composable body — so device samples (~50 Hz) re-run the draw/layer phase only,
// never recomposition.
// ─────────────────────────────────────────────────────────────────────────────

/** Feel/physics tunables (NOT design tokens — these are motion physics, kept swappable). */
data class TiltConfig(
    val maxTiltDeg: Float = 11f,        // ±11° — "Subtle" +20% (red-pen: the card needed more presence)
    val pitchGain: Float = 1.35f,       // up-down reads weaker on a wide, short card → extra pitch sensitivity
    val maxInputRad: Float = 0.45f,     // deviation-from-baseline (~26°) that maps to full tilt
    val parallaxDp: Float = 8f,         // translationX/Y travel at full tilt
    val cameraDistanceDp: Float = 16f,  // .dp.toPx() == ×density — the working idiom; up from default ~8
    val signalAlpha: Float = 0.20f,     // fast EMA — de-jitter (τ≈100 ms @ 50 Hz)
    val baselineAlpha: Float = 0.015f,  // slow baseline chase (τ≈1.3 s) → held angle eases back to flat
    val shineAlpha: Float = 0.26f,      // specular band peak alpha (at full tilt)
    val shineRestAlpha: Float = 0.10f,  // persistent gloss when still — a real card stays shiny (0 = shine only while moving)
    val shineBandWidth: Float = 0.34f,  // half-width of the band, as a fraction of the card diagonal
    val edgeGlintDp: Float = 2.5f,      // rim stroke width; the clip keeps the inner half → ~1.25dp of lit edge
    val iridescent: Boolean = true,     // his pick: aurora-tinted shine (false = plain white sheen)
    val glowParallax: Boolean = true,   // shift the corner-glow with tilt → aurora "catches the light"
) {
    companion object { val Default = TiltConfig() }
}

/**
 * Display-only, ephemeral tilt state. The sensor listener writes [pitchDeg]/[rollDeg]/[active];
 * the modifiers read them inside draw/layer lambdas. Float-state so those reads observe without
 * recomposing. Scratch arrays + smoothing/baseline fields are allocated once (no GC at 50 Hz).
 */
@Stable
class DeviceTiltState {
    private val pitchState = mutableFloatStateOf(0f)
    private val rollState = mutableFloatStateOf(0f)
    private val activeState = mutableStateOf(false)
    val pitchDeg: Float get() = pitchState.floatValue
    val rollDeg: Float get() = rollState.floatValue
    val active: Boolean get() = activeState.value

    private val rMat = FloatArray(9)
    private val orient = FloatArray(3)
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var sPitch = Float.NaN   // smoothed signal
    private var sRoll = Float.NaN
    private var bPitch = 0f           // slow baseline (the adaptive "neutral")
    private var bRoll = 0f

    /** Feed a rotation-vector sample (radians, from getOrientation). */
    internal fun onRotationVector(values: FloatArray, c: TiltConfig) {
        SensorManager.getRotationMatrixFromVector(rMat, values)
        SensorManager.getOrientation(rMat, orient)
        push(orient[1], orient[2], c)   // [1]=pitch, [2]=roll
    }

    /** Accelerometer fallback: low-pass gravity → pitch/roll. */
    internal fun onAccelerometer(values: FloatArray, c: TiltConfig) {
        if (!hasGravity) {
            gravity[0] = values[0]; gravity[1] = values[1]; gravity[2] = values[2]; hasGravity = true
        } else {
            val a = 0.15f
            gravity[0] += a * (values[0] - gravity[0])
            gravity[1] += a * (values[1] - gravity[1])
            gravity[2] += a * (values[2] - gravity[2])
        }
        val gx = gravity[0]; val gy = gravity[1]; val gz = gravity[2]
        push(atan2(-gy, hypot(gx, gz)), atan2(gx, gz), c)
    }

    private fun push(pitch: Float, roll: Float, c: TiltConfig) {
        // Fast EMA (wrap-safe so the ±π seam doesn't snap); seed baseline to the first sample.
        sPitch = if (sPitch.isNaN()) pitch.also { bPitch = it } else sPitch + c.signalAlpha * angleDelta(pitch, sPitch)
        sRoll = if (sRoll.isNaN()) roll.also { bRoll = it } else sRoll + c.signalAlpha * angleDelta(roll, sRoll)
        // Slow baseline chase → the resting pose adapts, so a held angle settles back toward flat.
        bPitch += c.baselineAlpha * (sPitch - bPitch)
        bRoll += c.baselineAlpha * (sRoll - bRoll)
        // Pitch gets a gain boost — the card is wide and short, so an up-down slant foreshortens far less
        // than left-right; the gain makes both axes *read* equally strong at the same physical motion.
        val devP = ((sPitch - bPitch) * c.pitchGain).coerceIn(-c.maxInputRad, c.maxInputRad)
        val devR = (sRoll - bRoll).coerceIn(-c.maxInputRad, c.maxInputRad)
        // Negated at the source so the WHOLE effect — slant, parallax, shine slide, glow — mirrors together
        // (device-tuned direction; one sign here flips every consumer consistently).
        pitchState.floatValue = -devP / c.maxInputRad * c.maxTiltDeg
        rollState.floatValue = -devR / c.maxInputRad * c.maxTiltDeg
        activeState.value = true
    }

    /** Settle flat + clear smoothing so the next resume re-seeds the baseline cleanly. */
    internal fun reset() {
        sPitch = Float.NaN; sRoll = Float.NaN
        bPitch = 0f; bRoll = 0f
        hasGravity = false
        pitchState.floatValue = 0f; rollState.floatValue = 0f
        activeState.value = false
    }
}

/** Wrapped angular delta (target − current) in [−π, π], via atan2 so the ±π seam is smooth. */
private fun angleDelta(target: Float, current: Float): Float {
    val d = target - current
    return atan2(sin(d), cos(d))
}

/**
 * Lifecycle-scoped device-tilt hook. Picks the best motion sensor (game-rotation-vector → rotation-vector
 * → accelerometer), registers on ON_RESUME and unregisters on ON_PAUSE / dispose (so it never drains in the
 * background or on a tab switch). No-ops when no sensor exists or "remove animations" (reduce-motion) is on
 * → the card renders flat. Mirrors the DisposableEffect idiom in `rememberCaptureGrants`.
 */
@Composable
fun rememberDeviceTilt(config: TiltConfig = TiltConfig.Default): DeviceTiltState {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { DeviceTiltState() }
    DisposableEffect(owner, config) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.let {
            it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val isAccel = sensor?.type == Sensor.TYPE_ACCELEROMETER
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (isAccel) state.onAccelerometer(e.values, config) else state.onRotationVector(e.values, config)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        fun start() {
            val reduceMotion = Settings.Global.getFloat(
                ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
            if (!reduceMotion && sm != null && sensor != null) {
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        fun stop() {
            sm?.unregisterListener(listener)
            state.reset()
        }
        // addObserver replays up to the current state, so ON_RESUME fires now if already resumed.
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            stop()
        }
    }
    return state
}

/**
 * The 3D slant. graphicsLayer block-form so the [state] reads happen in the layer phase (no recomposition).
 * rotationX/Y from pitch/roll deviation; a few-dp translation for the "moves in that direction" parallax;
 * cameraDistance tuned up from the default for a subtle, undistorted perspective. Tilt signs are empirical
 * — flip on device if the card tracks backwards.
 */
fun Modifier.tilt3d(state: DeviceTiltState, config: TiltConfig = TiltConfig.Default): Modifier =
    this.graphicsLayer {
        // Signs device-tuned so the card tracks WITH the phone (tilt toward you → top tips toward you).
        rotationX = state.pitchDeg
        rotationY = state.rollDeg
        translationX = state.rollDeg / config.maxTiltDeg * config.parallaxDp.dp.toPx()
        translationY = -state.pitchDeg / config.maxTiltDeg * config.parallaxDp.dp.toPx()
        cameraDistance = config.cameraDistanceDp.dp.toPx()   // ×density (== dp.toPx) — the working idiom
    }

/**
 * The moving aurora shine, drawn per-frame in [drawWithContent] (NOT drawWithCache — that caches on size and
 * wouldn't move). Three layers, all reading [state] so only the draw phase re-runs:
 *  1. the corner glow — always drawn (so a sensor-less card keeps its signature glow), parallaxed when active,
 *     under the content so the labels stay legible;
 *  2. an iridescent specular band whose position AND alpha track tilt → invisible at rest, sliding & brightening
 *     toward the lifted edge as the card tilts, its hue shifting along the aurora ramp. Drawn OVER the content
 *     (red-pen): light passing across a physical card washes over the embossed number and the account chips,
 *     not just the surface around them;
 *  3. an edge glint — the same moving gradient stroked along the card's rim, brighter than the fill band, so
 *     the card's edges catch the light like a real card held under a lamp.
 */
fun Modifier.specularShine(
    state: DeviceTiltState,
    config: TiltConfig = TiltConfig.Default,
    shape: Shape = WalletShapes.extraLarge,
): Modifier = this
    .clip(shape)
    .drawWithContent {
        val glowDx = if (state.active && config.glowParallax) -state.rollDeg / config.maxTiltDeg * size.width * 0.10f else 0f
        val glowDy = if (state.active && config.glowParallax) state.pitchDeg / config.maxTiltDeg * size.height * 0.10f else 0f
        drawRect(cornerGlowBrush(size, Offset(size.width + glowDx, -size.height * 0.10f + glowDy)))
        drawContent()

        if (!state.active) return@drawWithContent
        // Gloss alpha: a persistent rest floor that brightens toward the peak with tilt magnitude.
        val tiltMag = ((abs(state.rollDeg) + abs(state.pitchDeg)) / config.maxTiltDeg).coerceIn(0f, 1f)
        val a = config.shineRestAlpha + (config.shineAlpha - config.shineRestAlpha) * tiltMag
        if (a <= 0.004f) return@drawWithContent
        // Band centre as a fraction of the card diagonal; roll slides it, pitch nudges it.
        val p = 0.5f + (state.rollDeg / config.maxTiltDeg) * 0.5f - (state.pitchDeg / config.maxTiltDeg) * 0.25f
        val tint = (if (config.iridescent) auroraAt(p.coerceIn(0f, 1f)) else White).copy(alpha = a * 0.6f)
        val core = GlassSpecular.copy(alpha = a)
        val w = config.shineBandWidth
        // Fixed stops on a shifted gradient line → always-valid positions, band can slide off-edge naturally.
        val start = Offset(size.width * (p - w), size.height * (p - w))
        val end = Offset(size.width * (p + w), size.height * (p + w))
        drawRect(
            Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.30f to tint,
                    0.50f to core,
                    0.70f to tint,
                    1.0f to Color.Transparent,
                ),
                start = start, end = end,
            ),
        )
        // The rim catches the same light, harder — stroke the outline where the band crosses it.
        val edge = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.30f to tint.copy(alpha = (a * 1.4f).coerceAtMost(1f)),
                0.50f to core.copy(alpha = (a * 2.4f).coerceAtMost(1f)),
                0.70f to tint.copy(alpha = (a * 1.4f).coerceAtMost(1f)),
                1.0f to Color.Transparent,
            ),
            start = start, end = end,
        )
        drawOutline(shape.createOutline(size, layoutDirection, this), edge, style = Stroke(config.edgeGlintDp.dp.toPx()))
    }
