package com.goushik.upiwallet.ui.insights

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.goushik.upiwallet.capture.LocationSource
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.insights.Cities
import com.goushik.upiwallet.domain.insights.CityMap
import com.goushik.upiwallet.domain.insights.CityMapLoader
import com.goushik.upiwallet.domain.insights.nearestLabel
import com.goushik.upiwallet.domain.insights.GeoPoint
import com.goushik.upiwallet.domain.insights.MappedCity
import com.goushik.upiwallet.domain.insights.MapCluster
import com.goushik.upiwallet.domain.insights.MapData
import com.goushik.upiwallet.domain.insights.MapProjection
import com.goushik.upiwallet.domain.insights.MapTxn
import com.goushik.upiwallet.domain.insights.MapView
import com.goushik.upiwallet.domain.insights.PlaceSheet
import com.goushik.upiwallet.domain.insights.buildPlaceSheet
import com.goushik.upiwallet.domain.insights.clusterScreenPoints
import com.goushik.upiwallet.domain.insights.flatPanDelta
import com.goushik.upiwallet.domain.insights.groundDepth
import com.goushik.upiwallet.domain.insights.groundSquish
import com.goushik.upiwallet.domain.insights.groundTransform
import com.goushik.upiwallet.ui.theme.Amber500
import com.goushik.upiwallet.ui.theme.Coral500
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.Indigo900
import com.goushik.upiwallet.ui.theme.Inter
import com.goushik.upiwallet.ui.theme.Lavender300
import com.goushik.upiwallet.ui.theme.LiveLocation
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.SpaceGrotesk
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.auroraAt
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The "Ghost City" map (Insights → Map). A hand-drawn Compose Canvas, zero map deps — same discipline
 * as [InsightsChart]. A faint real-OSM skeleton (roads + water + locality labels, from the bundled
 * asset, traced ONCE into a cached [ImageBitmap]) sits under glow-only pins (size = amount, hue =
 * recency) and achromatic heat-wells (bloom = count, brightness = summed ₹). A paired-list strip + a
 * reconciliation footnote ("N of M payments have a location") close the honesty loop. Four empty
 * states cover opt-out / no-data / no-located / all-outside.
 *
 * Position is the rounded (~110 m) capture-time fix — where you *were*, not the merchant. The map is
 * the feeling; meaning is carried by the names in the list + the real locality labels.
 */
@Composable
fun InsightsMap(
    map: MapData,
    onOpenLocationSettings: () -> Unit,
    onOpenTransaction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The user's city-switcher pick (null = follow the auto-default). Declared BEFORE the empty-state
    // early-returns so its rememberSaveable slot is always reached — otherwise a transit through any
    // empty window (e.g. switching to a spend-free day) would forget the slot and silently reset the pick.
    var shownSlug by rememberSaveable { mutableStateOf<String?>(null) }

    // ── empty states — gated on the GLOBAL located situation (NOT the active city), so switching to a
    // city that has no pins still renders the map + switcher rather than a dead-end empty screen ──
    when {
        map.totalSpendCount == 0 ->
            return EmptyState(modifier, "No spend in this window", "Once you spend here, the places will show up on the map.")
        map.locatedCount == 0 && !map.locationEnabled ->
            return EmptyState(
                modifier,
                "Turn on the Spending map",
                "Let the app quietly note where you were when you pay, on this device only.",
                cta = "Open Spending map settings" to onOpenLocationSettings,
            )
        map.locatedCount == 0 ->
            return EmptyState(modifier, "No places yet", "Payments you make from here on will pin to where you were.")
        !map.hasAnyPlotted ->
            return EmptyState(
                modifier,
                "Outside your mapped cities",
                "${map.locatedCount} located ${plural(map.locatedCount, "payment")} fell outside Madurai, Chennai and Bengaluru.",
            )
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // The active (shown) city = the user's pick, else the auto-default (most-populated). EVERY per-city
    // remember below is keyed on `activeSlug`/`activePlotted`, AND both gesture `pointerInput`s are keyed
    // on `activeSlug` too, so switching cities fully re-derives the basemap, pins, pan/zoom, selection,
    // tilt AND the live gesture coroutines — never a stale half-switched state.
    val activeSlug = shownSlug ?: map.defaultCitySlug
    val activeCity = remember(activeSlug) { Cities.bySlug(activeSlug) }
    val activePlotted = remember(activeSlug, map.plottedByCity) { map.plottedFor(activeSlug) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }

    val cityMap = remember(activeSlug) { CityMapLoader.load(context, activeSlug) }

    var selected by remember(activeSlug, activePlotted.size) { mutableStateOf(-1) }
    // Pan/zoom view (layered on the pure projection). Resets when the active city changes.
    var view by remember(activeSlug) { mutableStateOf(MapView.Identity) }
    // The live "you are here" fix — DISPLAY-ONLY (lat/lng, never stored). Null = not located / outside.
    var youAreHere by remember(activeSlug) { mutableStateOf<GeoPoint?>(null) }
    var outsideHint by remember(activeSlug) { mutableStateOf(false) }
    // 2D ↔ 3D skyline. One animated `tilt∈[0,1]` drives BOTH the ground compression and the tower
    // extrusion, so the city "stands up". Selection (a cluster index) survives the toggle — the tilt is
    // a pure camera transform, NOT a re-cluster.
    var is3D by rememberSaveable(activeSlug) { mutableStateOf(false) }
    val tilt by animateFloatAsState(if (is3D) 1f else 0f, animationSpec = tween(650), label = "tilt")
    // Yaw (orbit) in degrees, driven by the two-finger twist. 3D-only: the EFFECTIVE yaw is yaw·tilt, so
    // it eases to north for free as the city sits back down to 2D (and resumes as it stands up again).
    var yawDeg by rememberSaveable(activeSlug) { mutableStateOf(0f) }

    val padPx = with(density) { 14.dp.toPx() }
    val clusterRadiusPx = with(density) { 26.dp.toPx() }
    val tapRadiusPx = with(density) { 30.dp.toPx() }
    val recency = remember(activePlotted) {
        val times = activePlotted.map { it.timestampEvent }
        val lo = times.minOrNull() ?: 0L; val hi = times.maxOrNull() ?: 0L
        { t: Long -> if (hi <= lo) 1f else ((t - lo).toFloat() / (hi - lo).toFloat()) }
    }
    val maxAmt = remember(activePlotted) { activePlotted.maxOfOrNull { it.amountPaise } ?: 1L }

    // The Place sheet overlays the WHOLE map area (map + paired list + footnote) per the locked
    // full-screen mockup: an open sheet then HIDES the paired list, whose exact-coord grouping would
    // otherwise show a second, different "<locality> · N payments" count beside the sheet's
    // screen-cluster count (the two definitions are each correct but read as a bug side by side).
    var selectedPlace by remember(activeSlug, activePlotted.size) { mutableStateOf<PlaceSheet?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Brush.verticalGradient(listOf(Indigo900.copy(0.35f), Indigo900.copy(0.55f))))
                    .border(1.dp, White.copy(0.10f), MaterialTheme.shapes.medium),
            ) {
                val cw = constraints.maxWidth
                val ch = constraints.maxHeight
                // Size is known at composition (no onSizeChanged round-trip) → valid projection on frame 1.
                val projection = remember(cw, ch, activeCity) {
                    if (cw <= 0 || ch <= 0) null
                    else MapProjection.fit(activeCity.center, activeCity.bounds, cw.toFloat(), ch.toFloat(), padPx)
                }
                // Vector road/water Paths projected ONCE — drawn each frame under the pan/zoom transform so
                // Skia re-rasterizes them crisp at any zoom (NO blurry bitmap upscale, NO per-frame rebuild).
                val roadPaths = remember(cityMap, projection) {
                    if (cityMap == null || projection == null) emptyList()
                    else cityMap.roads.map { projection.polyPath(it) }
                }
                val waterPaths = remember(cityMap, projection) {
                    if (cityMap == null || projection == null) emptyList()
                    else cityMap.water.map { projection.polyPath(it) }
                }
                // Base (un-transformed) pin positions, projected once.
                val baseProjected = remember(activePlotted, projection) {
                    if (projection == null) emptyList()
                    else activePlotted.map { projection.project(it.lat, it.lng) }
                }
                // Clusters live in TRANSFORMED screen space, recomputed on every zoom/pan → zooming into a
                // heat-well spreads its members past the radius and resolves it into individual pins.
                val clusters = remember(baseProjected, view) {
                    if (baseProjected.isEmpty()) emptyList()
                    else {
                        val n = baseProjected.size
                        val xs = FloatArray(n); val ys = FloatArray(n)
                        val amt = LongArray(n); val ts = LongArray(n)
                        baseProjected.forEachIndexed { i, b ->
                            val s = view.apply(b.x, b.y)
                            xs[i] = s.x; ys[i] = s.y
                            amt[i] = activePlotted[i].amountPaise; ts[i] = activePlotted[i].timestampEvent
                        }
                        clusterScreenPoints(xs, ys, amt, ts, clusterRadiusPx)
                    }
                }
                val maxSum = remember(clusters) { clusters.maxOfOrNull { it.sumPaise } ?: 1L }
                // rememberUpdatedState so the tap detector (keyed on Unit) always sees current values.
                val clustersLatest by rememberUpdatedState(clusters)
                val tiltLatest by rememberUpdatedState(tilt)
                val maxSumLatest by rememberUpdatedState(maxSum)
                val yawDegLatest by rememberUpdatedState(yawDeg)

                // Tap-a-pin → the Place sheet. `place` = the selected cluster's payments + an HONEST,
                // spread-aware accuracy radius (a SCREEN cluster can merge more than one ~110 m spot, so a
                // fixed "~110 m" halo would overstate precision). Recomputed when the selection or the
                // clusters change; a pan/zoom resets `selected` → `place` becomes null → the sheet closes.
                val place = remember(selected, clusters) {
                    clusters.getOrNull(selected)?.let { c -> buildPlaceSheet(c.memberIndices.map { activePlotted[it] }) }
                }
                // Back closes the sheet before leaving the screen (this innermost handler wins over AppShell's).
                BackHandler(enabled = selected in clusters.indices) { selected = -1 }
                // Mirror the tapped place UP so the sheet can overlay the WHOLE map area (incl. the
                // paired list) per the locked full-screen mockup — hiding the list's second count.
                LaunchedEffect(place) { selectedPlace = place }

                // "My location" → a fresh, display-only fix; recenters + drops the puck, or hints if outside.
                val locPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                        fetchLive(context, activeCity, projection, cw, ch, onView = { view = it }, onHere = { youAreHere = it }, onOutside = { outsideHint = it })
                    }
                }
                val onLocate = {
                    when {
                        !map.locationEnabled -> onOpenLocationSettings()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                            PackageManager.PERMISSION_GRANTED ->
                            locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else ->
                            fetchLive(context, activeCity, projection, cw, ch, onView = { view = it }, onHere = { youAreHere = it }, onOutside = { outsideHint = it })
                    }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        // Pinch-zoom + 1-finger pan (clamped so the city always fills the viewport).
                        // KEYED ON activeSlug: the per-city state holders (view/selected/is3D/yawDeg/
                        // outsideHint) are re-created on a city switch, but a pointerInput coroutine only
                        // re-captures them if its KEY changes — without activeSlug here it would keep
                        // writing the ORPHANED previous-city state and every gesture would go dead.
                        .pointerInput(cw, ch, activeSlug) {
                            detectTransformGestures { centroid, pan, zoomChange, rotationChange ->
                                outsideHint = false
                                // `selected` indexes `clusters`, recomputed (different count) as zoom merges/
                                // splits wells — a held selection would point at the wrong pin mid-gesture.
                                selected = -1
                                // Two-finger twist orbits the skyline (3D only — flat 2D stays north-up).
                                if (is3D) yawDeg += rotationChange
                                // Un-rotate the screen-space drag into flat offset space so a single-finger
                                // pan follows the finger at ANY orbit angle (else it's inverted once spun).
                                val effYawRad = Math.toRadians((yawDeg * tilt).toDouble()).toFloat()
                                val d = flatPanDelta(pan.x, pan.y, effYawRad)
                                val moved = view.onGesture(centroid.x, centroid.y, d.x, d.y, zoomChange, MIN_ZOOM, MAX_ZOOM)
                                // Pan-clamp only when flat: a rotated/tilted city can't fill an axis-aligned
                                // viewport, so clamping would fight the orbit — let the void read as sky.
                                view = if (is3D) moved else moved.clampPan(cw.toFloat(), ch.toFloat())
                            }
                        }
                        // Tap selects a pin/tower; double-tap snaps back to the whole-city fit. Keyed on
                        // (cw, ch, activeSlug): activeSlug so it re-captures the fresh per-city state on a
                        // switch (same trap as the transform handler), cw/ch because onTap reads them via
                        // pick3D's pivot (cw/2, ch*0.55).
                        .pointerInput(cw, ch, activeSlug) {
                            detectTapGestures(
                                onDoubleTap = { view = MapView.Identity; yawDeg = 0f; selected = -1 },
                                onTap = { tap ->
                                    val tt = tiltLatest
                                    // Flat → forgiving radius hit; tilted → hit the tower BODY (footprint→cap).
                                    val hit = if (tt < 0.4f) {
                                        nearestCluster(clustersLatest, tap, tapRadiusPx)
                                    } else {
                                        val yawRad = Math.toRadians((yawDegLatest * tt).toDouble()).toFloat()
                                        pick3D(clustersLatest, tap, tt, cw / 2f, ch * 0.55f, yawRad, maxSumLatest, ch * 0.24f, this)
                                    }
                                    selected = if (hit == selected) -1 else hit
                                },
                            )
                        },
                ) {
                    val sq = groundSquish(tilt)
                    val pivotY = size.height * 0.55f
                    val rotCx = size.width / 2f
                    val effYawDeg = yawDeg * tilt            // 3D-only: eases to 0 as the city flattens
                    val effYawRad = Math.toRadians(effYawDeg.toDouble()).toFloat()
                    // Ghost skeleton under ONE matrix = view → rotate(yaw) → squish (all affine). Ops post-
                    // concatenate, so listed outermost-first: squish, rotate, then the view (translate∘scale).
                    withTransform({
                        scale(1f, sq, pivot = Offset(0f, pivotY))                 // squish (outermost)
                        rotate(degrees = effYawDeg, pivot = Offset(rotCx, pivotY)) // orbit the ground
                        translate(view.offsetX, view.offsetY)                      // view (innermost)
                        scale(view.zoom, view.zoom, pivot = Offset.Zero)
                    }) {
                        val z = view.zoom
                        val fade = 1f - tilt * 0.5f
                        val waterStroke = Stroke(width = 2.4.dp.toPx() / z, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        waterPaths.forEach { drawPath(it, WaterColor.copy(alpha = 0.22f * fade), style = waterStroke) }
                        val roadStroke = Stroke(width = 1.1.dp.toPx() / z, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        roadPaths.forEach { drawPath(it, RoadColor.copy(alpha = 0.14f * fade), style = roadStroke) }
                    }
                    // Overlay (constant on-screen size). drawClusters morphs each flat pin (tilt 0) into its
                    // glass tower (tilt 1); labels/pools/puck ride the rotated ground but stay billboard.
                    projection?.let { proj ->
                        drawPoolsAndLabels(cityMap, proj, view, tilt, rotCx, pivotY, effYawRad, textMeasurer)
                        drawClusters(clusters, selected, activePlotted, maxAmt, maxSum, recency, tilt, rotCx, pivotY, effYawRad, textMeasurer)
                        youAreHere?.let { geo ->
                            val b = proj.project(geo.lat, geo.lng)
                            val s = view.apply(b.x, b.y)
                            val g = groundTransform(s.x, s.y, rotCx, pivotY, pivotY, effYawRad, tilt)
                            drawYouAreHere(Offset(g.x, g.y))
                        }
                    }
                }

                // city chip (top-centre) — tap to open the switcher + honest roster.
                CityChip(
                    name = activeCity.displayName,
                    onClick = { switcherOpen = true },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                )
                if (outsideHint) {
                    Text(
                        "You're outside ${activeCity.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 48.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(Indigo900.copy(0.82f))
                            .border(1.dp, White.copy(0.12f), MaterialTheme.shapes.large)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                // OSM attribution — required.
                Text(
                    "© OpenStreetMap",
                    style = TextStyle(fontFamily = Inter, fontSize = 9.sp, color = Lavender300.copy(0.55f)),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(Indigo900.copy(0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                // 2D/3D toggle — top-right corner of the map (city chip is top-centre, so no collision).
                TiltToggle(
                    is3D = is3D,
                    onToggle = { is3D = !is3D },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )
                // "My location" — bottom-right, on its own now.
                LocationButton(onClick = onLocate, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))

                // City switcher + honest roster — opens from the chip; dims the map, dismiss-on-scrim.
                CitySwitcher(
                    visible = switcherOpen,
                    map = map,
                    activeSlug = activeSlug,
                    onPick = { slug -> shownSlug = slug; switcherOpen = false },
                    onDismiss = { switcherOpen = false },
                )
            }

            Spacer(Modifier.height(12.dp))
            PairedList(activePlotted, cityMap)
            Spacer(Modifier.height(8.dp))
            Footnote(map)
        }
        // Scrim + Place sheet — the LAST child, so the full-inset scrim dims + consumes the whole
        // map area while the sheet's rows stay tappable above it (paired list hidden beneath it).
        PlaceSheetOverlay(
            place = selectedPlace,
            cityMap = cityMap,
            onDismiss = { selected = -1 },
            onOpenTransaction = onOpenTransaction,
        )
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

/** Fetch a fresh display-only fix and either recenter+puck (inside the active city) or flag "outside". */
private fun fetchLive(
    context: android.content.Context,
    activeCity: MappedCity,
    projection: MapProjection?,
    cw: Int,
    ch: Int,
    onView: (MapView) -> Unit,
    onHere: (GeoPoint?) -> Unit,
    onOutside: (Boolean) -> Unit,
) {
    LocationSource.requestLive(context) { lat, lng ->
        if (activeCity.bounds.contains(lat, lng)) {
            onOutside(false)
            onHere(GeoPoint(lat, lng))
            projection?.let { proj ->
                val b = proj.project(lat, lng)
                onView(MapView.centerOn(b.x, b.y, cw.toFloat(), ch.toFloat(), 3.5f))
            }
        } else {
            onHere(null)
            onOutside(true)
        }
    }
}

// ──────────────────────────────── basemap ────────────────────────────────

private val RoadColor = Lavender300.copy(alpha = 0.14f)
private val WaterColor = Violet500.copy(alpha = 0.22f)

/** Build a BASE-space screen Path from a flattened [lat,lng,…] polyline (projected once; the pan/zoom
 *  transform is applied at draw time, so these stay crisp at any zoom). */
private fun MapProjection.polyPath(flat: DoubleArray): Path {
    val path = Path()
    if (flat.size < 4) return path
    val p0 = project(flat[0], flat[1])
    path.moveTo(p0.x, p0.y)
    var i = 2
    while (i + 1 < flat.size) {
        val p = project(flat[i], flat[i + 1]); path.lineTo(p.x, p.y); i += 2
    }
    return path
}

// ──────────────────────────────── place pools + labels (live, de-cluttered) ────────────────────────────────

/** Pools + locality labels at CONSTANT on-screen size, repositioned by [view] then tilted by [tilt]
 *  (so they ride the ground but stay billboard/screen-aligned). */
private fun DrawScope.drawPoolsAndLabels(
    city: CityMap?,
    proj: MapProjection,
    view: MapView,
    tilt: Float,
    rotCx: Float,
    pivotY: Float,
    yawRad: Float,
    tm: TextMeasurer,
) {
    if (city == null) return
    val labelStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = Lavender300.copy(0.55f))
    val poolR = 42.dp.toPx()
    val minGap = 72.dp.toPx()
    val margin = 12.dp.toPx()
    val placed = ArrayList<Offset>()
    for (lbl in city.labels) {
        val b = proj.project(lbl.lat, lbl.lng)
        val s = view.apply(b.x, b.y)
        val p = groundTransform(s.x, s.y, rotCx, pivotY, pivotY, yawRad, tilt).let { Offset(it.x, it.y) }
        if (p.x < margin || p.y < margin || p.x > size.width - margin || p.y > size.height - margin) continue
        if (placed.any { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < minGap }) continue
        // soft place pool
        drawCircle(
            brush = Brush.radialGradient(
                listOf(White.copy(0.05f), Color.Transparent),
                center = Offset(p.x, p.y), radius = poolR,
            ),
            radius = poolR, center = Offset(p.x, p.y),
        )
        val layout = tm.measure(lbl.name, labelStyle)
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        placed.add(Offset(p.x, p.y))
    }
}

/** The live, display-only "you are here" puck — sky-cyan so it never reads as an aurora spend pin. A
 *  soft halo + cyan core + white ring (a touch larger than spend pins; it's the real-time GPS dot). */
private fun DrawScope.drawYouAreHere(center: Offset) {
    drawCircle(
        brush = Brush.radialGradient(listOf(LiveLocation.copy(0.45f), Color.Transparent), center, 22.dp.toPx()),
        radius = 22.dp.toPx(), center = center,
    )
    drawCircle(White, 6.5.dp.toPx(), center)
    drawCircle(LiveLocation, 5.dp.toPx(), center)
    drawCircle(White, 1.8.dp.toPx(), center)
}

// ──────────────────── clusters: flat pins (tilt 0) ↔ glass towers (tilt 1) ────────────────────

/** Tower height in px for a cluster, scaled to the tallest place's total ₹, grown by the tilt [t]. */
private fun towerHeightPx(sumPaise: Long, maxSum: Long, maxTowerPx: Float, t: Float): Float =
    sqrt((sumPaise.toFloat() / maxSum).coerceIn(0f, 1f)) * maxTowerPx * t

/** Tower (and footprint-disc) half-width in px — singletons are narrow, wells widen with count. */
private fun Density.clusterHalfWPx(count: Int): Float =
    (if (count == 1) 6.dp else 8.dp + (sqrt(count.toFloat()) * 2f).dp).toPx()

/**
 * Draw every located cluster, morphing each from its flat 2D form (tilt [t]=0 — IDENTICAL to the old
 * `drawPins`: glow pin / heat-well) into a glass tower standing on a ~110 m footprint disc (t=1). One
 * `t` interpolates: footprint + shaft fade IN, the 2D bloom/halo fades OUT, the head rises to the cap.
 * Painter-ordered far→near so near towers overlap correctly.
 */
private fun DrawScope.drawClusters(
    clusters: List<MapCluster>,
    selected: Int,
    plotted: List<MapTxn>,
    maxAmt: Long,
    maxSum: Long,
    recency: (Long) -> Float,
    t: Float,
    rotCx: Float,
    pivotY: Float,
    yawRad: Float,
    tm: TextMeasurer,
) {
    val s = groundSquish(t)
    val maxTowerPx = size.height * 0.24f
    val footRx = 16.dp.toPx()
    val footRy = footRx * s
    // The number crossfades: the flat 2D center label out by t=0.5, the cap chip in after t=0.5.
    val centerFade = (1f - 2f * t).coerceIn(0f, 1f)
    val chipFade = (2f * t - 1f).coerceIn(0f, 1f)

    // Painter's order: far→near by ROTATED ground depth (NOT the un-rotated y) so towers overlap right
    // once the city spins past ~90°.
    val order = clusters.indices.sortedBy { groundDepth(clusters[it].x, clusters[it].y, rotCx, pivotY, yawRad) }
    for (i in order) {
        val c = clusters[i]
        val g = groundTransform(c.x, c.y, rotCx, pivotY, pivotY, yawRad, t)
        val gx = g.x; val gy = g.y
        val towerH = towerHeightPx(c.sumPaise, maxSum, maxTowerPx, t)
        val capY = gy - towerH
        val capHue = auroraAt(recency(c.latestMs))

        // footprint disc (fades in with tilt): a soft glow + a dark inner shadow ellipse
        if (t > 0.02f) {
            drawOval(
                brush = Brush.radialGradient(listOf(Lavender300.copy(0.16f * t), Color.Transparent), Offset(gx, gy), footRx * 1.4f),
                topLeft = Offset(gx - footRx * 1.4f, gy - footRy * 1.4f),
                size = Size(footRx * 2.8f, footRy * 2.8f),
            )
            drawOval(
                color = Indigo900.copy(0.5f * t),
                topLeft = Offset(gx - footRx * 0.6f, gy - footRy * 0.6f),
                size = Size(footRx * 1.2f, footRy * 1.2f),
            )
        }

        if (c.count == 1) {
            val txn = plotted[c.memberIndices[0]]
            val hue = auroraAt(recency(txn.timestampEvent))
            val frac = sqrt((txn.amountPaise.toFloat() / maxAmt).coerceIn(0f, 1f))
            val coreR = 3.dp.toPx() + frac * 4.dp.toPx()
            if (towerH > 0.5f) drawTowerShaft(gx, capY, gy, clusterHalfWPx(1), hue, t)
            // 2D halo fades out as it stands up
            if (frac > 0.30f && centerFade > 0f) {
                val haloR = coreR * 3.0f
                drawCircle(Brush.radialGradient(listOf(hue.copy(0.55f * centerFade), Color.Transparent), Offset(gx, capY), haloR), haloR, Offset(gx, capY))
            }
            drawCircle(hue, coreR, Offset(gx, capY))
            drawCircle(White, coreR * 0.34f, Offset(gx, capY))
            // prominent singletons get their spend on a CAP chip in 3D (small bare dots stay clean);
            // the selected tower's chip is suppressed — its tooltip takes over.
            if (frac > 0.30f && chipFade > 0f && i != selected) drawCapChip(gx, capY, compactRupees(txn.amountPaise), chipFade, tm)
        } else {
            // heat-well → cluster tower. The flat bloom + center count/₹ fade out; a clean recency cap
            // dot + a "N · ₹sum" CAP chip fade in (no base plinth — count + spend live on top).
            if (centerFade > 0f) {
                val bloomR = 16.dp.toPx() + sqrt(c.count.toFloat()) * 7.dp.toPx()
                val brightness = sqrt((c.sumPaise.toFloat() / (maxAmt * 3f)).coerceIn(0f, 1f)).coerceIn(0.25f, 0.6f) * centerFade
                drawCircle(Brush.radialGradient(listOf(Lavender300.copy(brightness), Lavender300.copy(brightness * 0.4f), Color.Transparent), Offset(gx, gy), bloomR), bloomR, Offset(gx, gy))
                // Count over spend, measured once (no colour → cacheable) and centred as one block on the disc
                // centre — then size the disc to ENCLOSE that block, so the ₹ amount can't spill out on small
                // clusters. A circle clears a rectangle only past its corner → hypot, not just width/height.
                val countLayout = tm.measure(c.count.toString(), TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                val sumLayout = tm.measure(compactRupees(c.sumPaise), TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 8.sp))
                val gap = 1.dp.toPx()
                val blockH = countLayout.size.height + gap + sumLayout.size.height
                val blockHalfW = maxOf(countLayout.size.width, sumLayout.size.width) / 2f
                val discR = (12.dp.toPx() + sqrt(c.count.toFloat()) * 1.6.dp.toPx())
                    .coerceAtLeast(hypot(blockHalfW, blockH / 2f) + 3.5.dp.toPx())
                drawCircle(Indigo900.copy(0.6f * centerFade), discR, Offset(gx, gy))
                drawCircle(White.copy(0.5f * centerFade), discR, Offset(gx, gy), style = Stroke(1.3.dp.toPx()))
                val blockTop = gy - blockH / 2f
                drawText(countLayout, color = White.copy(centerFade), topLeft = Offset(gx - countLayout.size.width / 2f, blockTop))
                drawText(sumLayout, color = White.copy(0.78f * centerFade), topLeft = Offset(gx - sumLayout.size.width / 2f, blockTop + countLayout.size.height + gap))
            }
            if (towerH > 0.5f) drawTowerShaft(gx, capY, gy, clusterHalfWPx(c.count), capHue, t)
            // clean recency cap dot fades in at the top
            if (chipFade > 0f) {
                drawCircle(capHue.copy(chipFade), 4.dp.toPx(), Offset(gx, capY))
                drawCircle(White.copy(chipFade), 1.6.dp.toPx(), Offset(gx, capY))
            }
            if (chipFade > 0f && i != selected) drawCapChip(gx, capY, "${c.count} · ${compactRupees(c.sumPaise)}", chipFade, tm)
        }
    }

    // selection ring at the cap of the selected tower — the pin anchor; the detail now lives in the
    // Place sheet (the old floating tooltip is gone, superseded by the sheet in both 2D and 3D).
    if (selected in clusters.indices) {
        val c = clusters[selected]
        val g = groundTransform(c.x, c.y, rotCx, pivotY, pivotY, yawRad, t)
        val capY = g.y - towerHeightPx(c.sumPaise, maxSum, maxTowerPx, t)
        drawCircle(White.copy(0.18f), 9.dp.toPx(), Offset(g.x, capY), style = Stroke(1.5.dp.toPx()))
        drawCircle(White, 2.5.dp.toPx(), Offset(g.x, capY))
    }
}

/** A small glass pill floating just ABOVE a tower's cap, carrying its count + spend. Clamped to stay
 *  on-screen for tall towers near the top edge. */
private fun DrawScope.drawCapChip(cx: Float, capY: Float, text: String, alpha: Float, tm: TextMeasurer) {
    val layout = tm.measure(text, TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = White.copy(alpha)))
    val padH = 7.dp.toPx(); val padV = 3.dp.toPx()
    val w = layout.size.width + padH * 2; val h = layout.size.height + padV * 2
    val top = (capY - 8.dp.toPx() - h).coerceAtLeast(2.dp.toPx())   // float above the cap dot, kept on-screen
    val r = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(Indigo900.copy(0.78f * alpha), topLeft = Offset(cx - w / 2f, top), size = Size(w, h), cornerRadius = r)
    drawRoundRect(White.copy(0.16f * alpha), topLeft = Offset(cx - w / 2f, top), size = Size(w, h), cornerRadius = r, style = Stroke(1.dp.toPx()))
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, top + padV))
}

/** A single glass tower shaft — vertical aurora gradient (recency cap → magenta → violet base), [alpha]
 *  scaled by the tilt so it fades in as the city stands. */
private fun DrawScope.drawTowerShaft(gx: Float, capY: Float, baseY: Float, halfW: Float, capHue: Color, alpha: Float) {
    val brush = Brush.verticalGradient(
        0.0f to capHue.copy(alpha = 0.92f * alpha),
        0.45f to Magenta500.copy(alpha = 0.72f * alpha),
        1.0f to Violet500.copy(alpha = 0.55f * alpha),
        startY = capY, endY = baseY,
    )
    val r = CornerRadius(halfW * 0.5f, halfW * 0.5f)
    drawRoundRect(brush, topLeft = Offset(gx - halfW, capY), size = Size(halfW * 2, baseY - capY), cornerRadius = r)
    drawRoundRect(White.copy(0.12f * alpha), topLeft = Offset(gx - halfW, capY), size = Size(halfW * 2, baseY - capY), cornerRadius = r, style = Stroke(1.dp.toPx()))
}

/** 3D hit-test: the front-most tower whose BODY (footprint→cap screen rect) contains the tap. Uses the
 *  SAME rotated+tilted geometry as the draw, so a tap works at any orbit angle. */
private fun pick3D(
    clusters: List<MapCluster>,
    tap: Offset,
    t: Float,
    rotCx: Float,
    pivotY: Float,
    yawRad: Float,
    maxSum: Long,
    maxTowerPx: Float,
    density: Density,
): Int {
    with(density) {
        val footRy = 16.dp.toPx() * groundSquish(t)
        val tol = 6.dp.toPx()
        // near (greater rotated depth) first → the front-most tower wins.
        for (i in clusters.indices.sortedByDescending { groundDepth(clusters[it].x, clusters[it].y, rotCx, pivotY, yawRad) }) {
            val c = clusters[i]
            val g = groundTransform(c.x, c.y, rotCx, pivotY, pivotY, yawRad, t)
            val capY = g.y - towerHeightPx(c.sumPaise, maxSum, maxTowerPx, t)
            val halfW = clusterHalfWPx(c.count) + tol
            if (tap.x in (g.x - halfW)..(g.x + halfW) && tap.y in (capY - tol)..(g.y + footRy)) return i
        }
    }
    return -1
}

private fun nearestCluster(clusters: List<MapCluster>, tap: Offset, radiusPx: Float): Int {
    var best = -1; var bestD = radiusPx
    clusters.forEachIndexed { i, c ->
        val d = hypot((c.x - tap.x).toDouble(), (c.y - tap.y).toDouble()).toFloat()
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

// ──────────────────────────────── paired list + footnote ────────────────────────────────

@Composable
private fun PairedList(plotted: List<MapTxn>, cityMap: CityMap?) {
    val rows = remember(plotted) { buildPairRows(plotted, cityMap) }
    if (rows.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(MaterialTheme.shapes.medium, blur = false),
    ) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Spacer(Modifier.height(1.dp).fillMaxWidth().background(White.copy(0.06f)))
            PairRow(r)
        }
    }
}

@Composable
private fun PairRow(r: PairRowData) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(r.dot))
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            if (r.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(r.amount, style = MaterialTheme.typography.bodyMedium, color = RedDebit, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Footnote(map: MapData) {
    val located = map.locatedCount
    val total = map.totalSpendCount
    val extra = buildList {
        if (map.outsideCount > 0) add("${map.outsideCount} outside your cities")
    }.joinToString(" · ")
    Text(
        buildString {
            append("$located of $total ${plural(total, "payment")} ${if (located == 1) "has" else "have"} a location")
            if (extra.isNotEmpty()) append(" · $extra")
            append("  ·  where you were, not the shop")
        },
        style = MaterialTheme.typography.bodySmall,
        color = TextTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

// ──────────────────────────────── place sheet (tap a pin) ────────────────────────────────

/**
 * Scrim + the slide-up Place sheet for the tapped [place] (null = closed). The full-inset scrim dims
 * the map, consumes its gestures, and dismisses on tap; the sheet carries the honest accuracy locator
 * ("where you were, not the shops") + the payments-at-this-spot. The last non-null [place] is retained
 * so the content persists through the slide-out when `selected` flips back to −1.
 */
@Composable
private fun BoxScope.PlaceSheetOverlay(
    place: PlaceSheet?,
    cityMap: CityMap?,
    onDismiss: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    var shown by remember { mutableStateOf<PlaceSheet?>(null) }
    place?.let { shown = it }
    val visible = place != null

    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Indigo900.copy(0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        shown?.let { PlaceSheetPanel(it, cityMap, onOpenTransaction) }
    }
}

@Composable
private fun PlaceSheetPanel(
    place: PlaceSheet,
    cityMap: CityMap?,
    onOpenTransaction: (String) -> Unit,
) {
    val locality = remember(place, cityMap) {
        cityMap?.let { nearestLabel(it, place.centerLat, place.centerLng) } ?: "This spot"
    }
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Indigo900.copy(0.92f), Indigo900.copy(0.97f))))
            .border(1.dp, White.copy(0.12f), shape)
            // swallow taps on the sheet body so they never fall through to the dismiss scrim
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 18.dp)
            .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .size(width = 38.dp, height = 5.dp)
                .clip(CircleShape)
                .background(White.copy(0.22f)),
        )
        PlaceLocator(place, cityMap, Modifier.fillMaxWidth().height(128.dp))
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                locality,
                style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = TextPrimary),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Money.format(place.totalPaise),
                    style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "· ${place.count} ${plural(place.count, "payment")}",
                    style = TextStyle(fontFamily = Inter, fontSize = 11.sp, color = TextTertiary),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "where you were — not the shops",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .glassSurface(MaterialTheme.shapes.medium, blur = false)
                .verticalScroll(rememberScrollState()),
        ) {
            place.members.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(1.dp).fillMaxWidth().background(White.copy(0.06f)))
                PlaceRow(m, onClick = { onOpenTransaction(m.id) })
            }
        }
    }
}

@Composable
private fun PlaceRow(m: MapTxn, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(m.category)))
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(m.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            Text(DateTime.dayTime(m.timestampEvent), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.size(8.dp))
        Text("−${Money.format(m.amountPaise)}", style = MaterialTheme.typography.bodyMedium, color = RedDebit, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The locator mini-map: a fresh projection centred on [place], scaled so the spread-aware accuracy halo
 * DOMINATES the frame (the locked "where you were, not the shops" beat). Real OSM roads/water trace the
 * neighbourhood faintly; a single pin sits at the centroid; the caption reports the honest radius.
 */
@Composable
internal fun PlaceLocator(place: PlaceSheet, cityMap: CityMap?, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(
        modifier.clip(shape).background(Indigo900.copy(0.45f)).border(1.dp, White.copy(0.10f), shape),
    ) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        // Halo ≈ 72% of the shorter side → it dominates. scale (px/m) follows from the honest radius.
        val haloPx = minOf(cw, ch) * 0.36f
        val scalePxPerM = if (place.accuracyM > 0) haloPx / place.accuracyM else 0.0
        val proj = remember(place, cw, ch) {
            if (cw <= 0f || ch <= 0f) null
            else MapProjection.centeredAt(GeoPoint(place.centerLat, place.centerLng), scalePxPerM, cw, ch)
        }
        val roadPaths = remember(cityMap, proj) {
            if (cityMap == null || proj == null) emptyList() else cityMap.roads.map { proj.polyPath(it) }
        }
        val waterPaths = remember(cityMap, proj) {
            if (cityMap == null || proj == null) emptyList() else cityMap.water.map { proj.polyPath(it) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(cw / 2f, ch / 2f)   // == proj.project(place centre)
            val waterStroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            waterPaths.forEach { drawPath(it, WaterColor.copy(0.32f), style = waterStroke) }
            val roadStroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            roadPaths.forEach { drawPath(it, RoadColor.copy(0.30f), style = roadStroke) }
            // the dominating accuracy halo — low-alpha magenta fill + a dashed magenta ring
            drawCircle(Magenta500.copy(0.07f), haloPx, c)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()), 0f)
            drawCircle(Magenta500.copy(0.55f), haloPx, c, style = Stroke(1.4.dp.toPx(), pathEffect = dash))
            // centre pin: soft glow + magenta core + white ring + white pip
            drawCircle(Brush.radialGradient(listOf(Magenta500.copy(0.5f), Color.Transparent), c, 17.dp.toPx()), 17.dp.toPx(), c)
            drawCircle(Magenta500, 6.5.dp.toPx(), c)
            drawCircle(White, 6.5.dp.toPx(), c, style = Stroke(1.4.dp.toPx()))
            drawCircle(White, 2.2.dp.toPx(), c)
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Indigo900.copy(0.6f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(11.dp)) {
                drawCircle(
                    Lavender300, size.minDimension * 0.42f, Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f)),
                )
            }
            Spacer(Modifier.size(5.dp))
            Text(
                "~${roundedAccuracy(place.accuracyM)} m · neighbourhood",
                style = TextStyle(fontFamily = Inter, fontSize = 9.5.sp, color = TextSecondary),
            )
        }
    }
}

/** Round the honest accuracy radius to a calm caption number (nearest 10 m, never under the ~110 m floor). */
private fun roundedAccuracy(m: Double): Int = ((m / 10.0).roundToInt() * 10).coerceAtLeast(110)

// ──────────────────────────────── empty state ────────────────────────────────

@Composable
private fun EmptyState(
    modifier: Modifier,
    title: String,
    body: String,
    cta: Pair<String, () -> Unit>? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 36.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = TextTertiary, textAlign = TextAlign.Center)
            if (cta != null) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(Violet500.copy(0.22f))
                        .border(1.dp, Violet500, MaterialTheme.shapes.medium)
                        .clickable(onClick = cta.second)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) { Text(cta.first, style = MaterialTheme.typography.labelLarge, color = White) }
            }
        }
    }
}

@Composable
private fun CityChip(name: String, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(Indigo900.copy(0.78f))
            .border(1.dp, White.copy(0.12f), MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📍", fontSize = 11.sp)
        Spacer(Modifier.size(5.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(5.dp))
        Text("▾", style = MaterialTheme.typography.labelMedium, color = White.copy(0.7f))
    }
}

/**
 * City switcher + the honest roster (opens from the chip). Dims the map; lists every mapped city (tap to
 * switch — each row shows its located count + ₹), then info-only "Outside your cities" / "No location"
 * tallies (no basemap to switch to). The counts reconcile with the footnote: Σ cities + outside +
 * no-location == the located/total the footnote reports.
 */
@Composable
private fun BoxScope.CitySwitcher(
    visible: Boolean,
    map: MapData,
    activeSlug: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Indigo900.copy(0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 16.dp, end = 16.dp),
        enter = fadeIn() + slideInVertically { -it / 3 },
        exit = fadeOut() + slideOutVertically { -it / 3 },
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Brush.verticalGradient(listOf(Indigo900.copy(0.95f), Indigo900.copy(0.98f))))
                .border(1.dp, White.copy(0.14f), MaterialTheme.shapes.large)
                .padding(6.dp),
        ) {
            map.cityTallies.forEach { t ->
                SwitcherRow(
                    label = t.city.displayName,
                    right = if (t.count > 0) "${compactRupees(t.sumPaise)} · ${t.count}" else "—",
                    active = t.city.slug == activeSlug,
                    dim = t.count == 0,
                    onClick = { onPick(t.city.slug) },
                )
            }
            if (map.outsideCount > 0 || map.noLocationCount > 0) {
                Spacer(Modifier.padding(vertical = 4.dp, horizontal = 6.dp).height(1.dp).fillMaxWidth().background(White.copy(0.08f)))
            }
            if (map.outsideCount > 0) {
                SwitcherRow("Outside your cities", "${compactRupees(map.outsideSumPaise)} · ${map.outsideCount}", active = false, dim = true, onClick = null)
            }
            if (map.noLocationCount > 0) {
                SwitcherRow("No location", "${map.noLocationCount}", active = false, dim = true, onClick = null)
            }
        }
    }
}

/** One roster row. [onClick] null = info-only (Outside / No-location — nothing to switch to). */
@Composable
private fun SwitcherRow(label: String, right: String, active: Boolean, dim: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (active) Violet500.copy(0.22f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📍", fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim && !active) TextTertiary else TextPrimary,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            right,
            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
            color = if (dim && !active) TextTertiary else TextSecondary,
        )
    }
}

/** A clean glass 2D/3D toggle — shows the TARGET mode (mirrors the mockup). Violet when 3D is active. */
@Composable
private fun TiltToggle(is3D: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(if (is3D) Violet500.copy(0.30f) else Indigo900.copy(0.82f))
            .border(1.dp, if (is3D) Violet500 else White.copy(0.14f), MaterialTheme.shapes.large)
            .clickable(onClick = onToggle)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            // a clean filled isometric cube — three faces in white tints (top bright → sides dimmer).
            val w = size.width; val h = size.height; val cx = w / 2f
            val tp = Offset(cx, h * 0.08f)
            val ur = Offset(w * 0.92f, h * 0.30f); val ul = Offset(w * 0.08f, h * 0.30f)
            val c = Offset(cx, h * 0.52f)
            val lr = Offset(w * 0.92f, h * 0.70f); val ll = Offset(w * 0.08f, h * 0.70f)
            val b = Offset(cx, h * 0.92f)
            fun face(pts: List<Offset>, color: Color) {
                val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(p, color)
            }
            face(listOf(tp, ur, c, ul), White.copy(0.92f))           // top
            face(listOf(ul, c, b, ll), White.copy(0.34f))            // left
            face(listOf(ur, lr, b, c), White.copy(0.52f))            // right
            listOf(tp to ur, ur to lr, lr to b, b to ll, ll to ul, ul to tp, c to tp, c to ll, c to lr)
                .forEach { (a, z) -> drawLine(White.copy(0.7f), a, z, 1.dp.toPx()) }
        }
        Spacer(Modifier.size(6.dp))
        Text(if (is3D) "2D" else "3D", style = MaterialTheme.typography.labelMedium, color = White, fontWeight = FontWeight.SemiBold)
    }
}

/** The "my location" button — a glass disc with a hand-drawn crosshair (no icon dep). */
@Composable
private fun LocationButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Indigo900.copy(0.82f))
            .border(1.dp, White.copy(0.14f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * 0.34f
            val t = size.minDimension * 0.18f
            val col = White.copy(0.92f)
            val w = 1.6.dp.toPx()
            drawCircle(col, r, c, style = Stroke(w))
            drawCircle(col, 1.8.dp.toPx(), c)
            drawLine(col, Offset(c.x, c.y - r - t), Offset(c.x, c.y - r * 0.5f), w)
            drawLine(col, Offset(c.x, c.y + r * 0.5f), Offset(c.x, c.y + r + t), w)
            drawLine(col, Offset(c.x - r - t, c.y), Offset(c.x - r * 0.5f, c.y), w)
            drawLine(col, Offset(c.x + r * 0.5f, c.y), Offset(c.x + r + t, c.y), w)
        }
    }
}

// ──────────────────────────────── helpers ────────────────────────────────

private data class PairRowData(val dot: Color, val title: String, val subtitle: String, val amount: String)

/** Up to two list rows from the located data alone (no screen clusters): the busiest *place* (payments
 *  sharing a ~110 m rounded spot) + the most-recent payment. Size-independent and pure. */
private fun buildPairRows(plotted: List<MapTxn>, cityMap: CityMap?): List<PairRowData> {
    if (plotted.isEmpty()) return emptyList()
    val rows = ArrayList<PairRowData>(2)

    val places = plotted.groupBy { it.lat to it.lng }.values.toList()
    val busiest = places.maxByOrNull { it.size.toLong() * 1_000_000 + it.sumOf { p -> p.amountPaise } }!!
    if (busiest.size == 1) {
        rows.add(paymentRow(busiest[0], cityMap))
    } else {
        val loc = cityMap?.let { nearestLabel(it, busiest[0].lat, busiest[0].lng) }
        rows.add(
            PairRowData(
                dot = Lavender300,
                title = "${loc ?: "${busiest.size} payments"} · ${busiest.size} payments",
                subtitle = "your busiest spot this window",
                amount = "−${Money.format(busiest.sumOf { it.amountPaise })}",
            ),
        )
    }

    val recent = plotted.maxByOrNull { it.timestampEvent }!!
    if (busiest.size > 1 || recent.id != busiest[0].id) rows.add(paymentRow(recent, cityMap))
    return rows.take(2)
}

private fun paymentRow(t: MapTxn, cityMap: CityMap?): PairRowData {
    val loc = cityMap?.let { nearestLabel(it, t.lat, t.lng) }
    return PairRowData(
        dot = categoryColor(t.category),
        title = t.label,
        subtitle = listOfNotNull(loc, t.category).joinToString(" · "),
        amount = "−${Money.format(t.amountPaise)}",
    )
}

/** `nearestLabel` now lives in domain/insights/CityMap.kt (shared with the transaction-detail Location row). */

/** Category → a palette hue for the list dot. Calm, reused across the aurora vocabulary; exact hues
 *  are easy to red-pen later. Uncategorized falls to a muted tertiary. */
internal fun categoryColor(label: String?): Color = when (Category.fromLabel(label)) {
    Category.FOOD -> Amber500
    Category.GROCERIES -> GreenCredit
    Category.TRANSPORT -> Violet500
    Category.TRAVEL -> Violet500
    Category.BILLS -> Coral500
    Category.RENT -> Magenta500
    Category.SHOPPING -> Coral500
    Category.ENTERTAINMENT -> Lavender300
    Category.HEALTH -> GreenCredit
    Category.SUBSCRIPTIONS -> Magenta500
    Category.SELF_TRANSFER -> Lavender300
    Category.OTHER -> TextTertiary
    null -> TextTertiary
}

/** Compact rupees for a tight label: ₹500 / ₹1.2k / ₹2L. */
private fun compactRupees(paise: Long): String {
    val rs = paise / 100
    return when {
        rs < 1000 -> "₹$rs"
        rs < 100_000 -> "₹${trimOne(rs / 1000.0)}k"
        else -> "₹${trimOne(rs / 100_000.0)}L"
    }
}

private fun trimOne(d: Double): String {
    val r = (d * 10).roundToInt() / 10.0
    return if (r == kotlin.math.floor(r)) r.toInt().toString() else r.toString()
}

private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
