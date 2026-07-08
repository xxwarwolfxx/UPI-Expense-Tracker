package com.goushik.upiwallet.domain.insights

import kotlin.math.cos
import kotlin.math.sin

/**
 * PURE map geometry for the Insights "Ghost City" map — peer of [InsightsBuckets]. No Compose, no
 * Android: just doubles + kotlin.math, so it is host unit-testable. The Composable ([InsightsMap])
 * converts [MapPoint] → Compose Offset and supplies the canvas size; everything spatial lives here.
 *
 * Projection = per-city EQUIRECTANGULAR about the city centre (good enough at city scale, no datum
 * fuss), then bbox-fit to the canvas rect with a UNIFORM scale (preserve aspect → the city's shape is
 * undistorted) and a NORTH-UP flip (increasing latitude → decreasing screen-y). Build once per
 * (canvas size, city); [project] is then a cheap affine map reused for every road vertex and pin.
 */

/** A geographic point in degrees. */
data class GeoPoint(val lat: Double, val lng: Double)

/** A geographic bounding box, degrees. south < north, west < east. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Half-open-ish inclusive membership — a fix sits inside this city's mapped area. */
    fun contains(lat: Double, lng: Double): Boolean =
        lat in south..north && lng in west..east
}

/** A projected screen point in px, top-left origin, y-down (the Compose Canvas convention). */
data class MapPoint(val x: Float, val y: Float)

class MapProjection private constructor(
    private val lat0: Double,
    private val lng0: Double,
    private val mPerDegLat: Double,
    private val mPerDegLng: Double,
    private val scale: Double,        // px per metre
    private val originX: Double,      // left margin (px) after centring the fitted content
    private val originY: Double,      // top margin (px)
    private val worldLeft: Double,    // world metres at the bbox's west edge (relative to centre)
    private val worldTop: Double,     // world metres at the bbox's north edge (relative to centre)
) {
    /** Project a lat/lng (degrees) to a screen point (px). North is up; east is right. */
    fun project(lat: Double, lng: Double): MapPoint {
        val wx = (lng - lng0) * mPerDegLng
        val wy = (lat - lat0) * mPerDegLat
        val sx = originX + (wx - worldLeft) * scale
        val sy = originY + (worldTop - wy) * scale     // the north-up flip
        return MapPoint(sx.toFloat(), sy.toFloat())
    }

    companion object {
        /** Metres per degree of latitude (mean). Longitude scales this by cos(latitude). */
        private const val M_PER_DEG = 111_320.0

        /**
         * Fit a city ([center] + [bounds]) into a [width]×[height] px canvas, leaving [paddingPx] on
         * every side. Uniform "contain" scale: the whole bbox is visible and centred; the city's
         * aspect ratio is preserved so its skeleton reads (radial Madurai stays radial).
         */
        fun fit(
            center: GeoPoint,
            bounds: GeoBounds,
            width: Float,
            height: Float,
            paddingPx: Float = 0f,
        ): MapProjection {
            val lat0Rad = Math.toRadians(center.lat)               // cos needs RADIANS, not degrees
            val mPerDegLat = M_PER_DEG
            val mPerDegLng = M_PER_DEG * cos(lat0Rad)

            val worldLeft = (bounds.west - center.lng) * mPerDegLng
            val worldRight = (bounds.east - center.lng) * mPerDegLng
            val worldBottom = (bounds.south - center.lat) * mPerDegLat
            val worldTop = (bounds.north - center.lat) * mPerDegLat
            val worldW = (worldRight - worldLeft).coerceAtLeast(1e-6)
            val worldH = (worldTop - worldBottom).coerceAtLeast(1e-6)

            val availW = (width - 2 * paddingPx).coerceAtLeast(1f).toDouble()
            val availH = (height - 2 * paddingPx).coerceAtLeast(1f).toDouble()
            val scale = minOf(availW / worldW, availH / worldH)    // contain → both dims fit

            val fittedW = worldW * scale
            val fittedH = worldH * scale
            val originX = (width - fittedW) / 2.0                  // centre the fitted content
            val originY = (height - fittedH) / 2.0

            return MapProjection(
                lat0 = center.lat, lng0 = center.lng,
                mPerDegLat = mPerDegLat, mPerDegLng = mPerDegLng,
                scale = scale, originX = originX, originY = originY,
                worldLeft = worldLeft, worldTop = worldTop,
            )
        }

        /**
         * A projection CENTRED on [center] at an explicit [scalePxPerM] (px per metre), with the centre
         * pinned to the middle of a [width]×[height] canvas. Unlike [fit] (bbox "contain"), this fixes
         * the scale directly — used by the Place-sheet locator, where the dominating ~110 m accuracy
         * halo must render at a known pixel size regardless of the spot's geographic spread. Same
         * conventions as [project]: north is up, east is right. [project] is unchanged.
         */
        fun centeredAt(
            center: GeoPoint,
            scalePxPerM: Double,
            width: Float,
            height: Float,
        ): MapProjection {
            val lat0Rad = Math.toRadians(center.lat)
            return MapProjection(
                lat0 = center.lat, lng0 = center.lng,
                mPerDegLat = M_PER_DEG, mPerDegLng = M_PER_DEG * cos(lat0Rad),
                scale = scalePxPerM,
                originX = width / 2.0, originY = height / 2.0,
                worldLeft = 0.0, worldTop = 0.0,    // centre's world metres are (0,0) → lands at the middle
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen-space clustering — pins that land within [radiusPx] of each other collapse into one
// heat-well (clusters = density). Greedy single pass in input order (deterministic for tests). With
// coords rounded to ~110 m, repeat visits to one place already share a screen point, so this mostly
// merges genuinely-adjacent places. count == 1 → a glow pin; count >= 2 → a heat-well.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One cluster of located payments. [x]/[y] is the centroid in px; [memberIndices] point back into the
 * caller's parallel input arrays so a tap can resolve to the underlying payments.
 */
data class MapCluster(
    val x: Float,
    val y: Float,
    val count: Int,
    val sumPaise: Long,
    val latestMs: Long,            // most-recent member's timestamp → drives the recency hue
    val memberIndices: List<Int>,
)

/**
 * Cluster parallel arrays of already-projected screen points. [xs]/[ys] are px; [amounts] paise;
 * [times] epoch-ms. All four must be the same length. Returns clusters in first-seen order.
 */
fun clusterScreenPoints(
    xs: FloatArray,
    ys: FloatArray,
    amounts: LongArray,
    times: LongArray,
    radiusPx: Float,
): List<MapCluster> {
    val n = xs.size
    require(ys.size == n && amounts.size == n && times.size == n) { "parallel arrays must match" }
    if (n == 0) return emptyList()

    val r2 = radiusPx.toDouble() * radiusPx
    // Mutable accumulators per cluster.
    val cx = ArrayList<Double>()
    val cy = ArrayList<Double>()
    val sumX = ArrayList<Double>()
    val sumY = ArrayList<Double>()
    val count = ArrayList<Int>()
    val sumPaise = ArrayList<Long>()
    val latest = ArrayList<Long>()
    val members = ArrayList<MutableList<Int>>()

    for (i in 0 until n) {
        val xi = xs[i].toDouble()
        val yi = ys[i].toDouble()
        var hit = -1
        for (c in cx.indices) {
            val dx = xi - cx[c]
            val dy = yi - cy[c]
            if (dx * dx + dy * dy <= r2) { hit = c; break }
        }
        if (hit < 0) {
            cx.add(xi); cy.add(yi)
            sumX.add(xi); sumY.add(yi)
            count.add(1); sumPaise.add(amounts[i]); latest.add(times[i])
            members.add(mutableListOf(i))
        } else {
            sumX[hit] = sumX[hit] + xi
            sumY[hit] = sumY[hit] + yi
            count[hit] = count[hit] + 1
            cx[hit] = sumX[hit] / count[hit]            // running-mean centroid
            cy[hit] = sumY[hit] / count[hit]
            sumPaise[hit] = sumPaise[hit] + amounts[i]
            if (times[i] > latest[hit]) latest[hit] = times[i]
            members[hit].add(i)
        }
    }

    return cx.indices.map { c ->
        MapCluster(
            x = cx[c].toFloat(),
            y = cy[c].toFloat(),
            count = count[c],
            sumPaise = sumPaise[c],
            latestMs = latest[c],
            memberIndices = members[c],
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pan/zoom view transform — a PURE layer ON TOP of the base [MapProjection.fit] (which stays the
// untouched, unit-tested bbox-fit). A base screen point `b` (from `project`) shows at `b*zoom + offset`.
// Pins/labels/clusters/tap-hit-test all live in this transformed screen space, so they stay aligned
// with the basemap drawn under the same transform — and pin-tap keeps working after a pan/zoom.
// ─────────────────────────────────────────────────────────────────────────────

data class MapView(val zoom: Float, val offsetX: Float, val offsetY: Float) {
    /** Base screen point → on-screen point under the current pan/zoom. */
    fun apply(x: Float, y: Float): MapPoint = MapPoint(x * zoom + offsetX, y * zoom + offsetY)

    /**
     * A pinch about [cx],[cy] (screen px) by multiplicative [zoomChange] plus a [panX]/[panY] drag,
     * clamped to [minZoom]..[maxZoom]. Keeps the base point under the centroid fixed (the natural feel),
     * then applies the pan. Caller should [clampPan] the result.
     */
    fun onGesture(
        cx: Float, cy: Float, panX: Float, panY: Float, zoomChange: Float,
        minZoom: Float, maxZoom: Float,
    ): MapView {
        val newZoom = (zoom * zoomChange).coerceIn(minZoom, maxZoom)
        val k = newZoom / zoom                       // effective change after clamping
        val nx = cx - (cx - offsetX) * k + panX
        val ny = cy - (cy - offsetY) * k + panY
        return MapView(newZoom, nx, ny)
    }

    /** Clamp the pan so the city (base content ≈ the [w]×[h] canvas at zoom 1) always fills the viewport
     *  — no panning into empty void. At zoom 1 this pins offset to (0,0) = the exact fit view. */
    fun clampPan(w: Float, h: Float): MapView =
        MapView(zoom, offsetX.coerceIn(w * (1 - zoom), 0f), offsetY.coerceIn(h * (1 - zoom), 0f))

    companion object {
        /** zoom 1, no pan = the base bbox-fit (the non-interactive view). */
        val Identity = MapView(1f, 0f, 0f)

        /** A view centring base point [bx],[by] in the [w]×[h] canvas at [targetZoom] (then clamp). */
        fun centerOn(bx: Float, by: Float, w: Float, h: Float, targetZoom: Float): MapView =
            MapView(targetZoom, w / 2f - bx * targetZoom, h / 2f - by * targetZoom).clampPan(w, h)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tilt — the 3D "spending skyline" camera. A PURE oblique y-compression applied ON TOP of (after)
// the 2D [MapView] (screen2D → screen3D), so pan/zoom math stays untouched. t=0 → flat (identity);
// t→1 → the ground compresses toward a pivot row, freeing the upper canvas for towers that extrude
// straight up. x is unchanged (a shear is a later nicety). The compression is affine in screen-y
// (y' = s·y + (1−s)·pivot), so the basemap can be tilted by composing it into one Canvas matrix.
// ─────────────────────────────────────────────────────────────────────────────

/** The y-axis scale at tilt [t]: 1 (flat) → [MAX_TILT_SQUISH] (full skyline). */
const val MAX_TILT_SQUISH = 0.55f

fun groundSquish(t: Float): Float = 1f - t.coerceIn(0f, 1f) * (1f - MAX_TILT_SQUISH)

/** Compress a 2D screen-y toward [pivotY] by the tilt [t]. [pivotY] is a fixed point (maps to itself). */
fun tiltY(screenY: Float, pivotY: Float, t: Float): Float {
    val s = groundSquish(t)
    return s * screenY + (1f - s) * pivotY
}

/**
 * The full ground camera for the 3D skyline: take a flat (post-[MapView]) screen point, **rotate** it in
 * the ground plane about ([cx],[cy]) by [yawRad], **then** y-squish about [pivotY] by pitch [t]. Towers
 * still extrude straight UP in screen space — only the ground/footprints/roads orbit (the SimCity look).
 * yaw=0,t=0 → identity; yaw=0 → exactly [tiltY]. Pure → unit-testable, composes after pan/zoom.
 *
 * A ground circle stays a circle under yaw, so under the squish it foreshortens to an ellipse with a
 * VERTICAL minor axis at any yaw — the footprint `ry = rx·groundSquish(t)` rule holds unchanged.
 */
fun groundTransform(px: Float, py: Float, cx: Float, cy: Float, pivotY: Float, yawRad: Float, t: Float): MapPoint {
    val dx = px - cx; val dy = py - cy
    val c = cos(yawRad.toDouble()).toFloat(); val s = sin(yawRad.toDouble()).toFloat()
    val rx = cx + dx * c - dy * s
    val ry = cy + dx * s + dy * c
    return MapPoint(rx, tiltY(ry, pivotY, t))
}

/** The rotated ground depth (pre-squish rotated y). Painter's order must sort far→near by THIS, not the
 *  un-rotated y — else towers overlap backwards once the city spins past ~90°. Pitch-independent. */
fun groundDepth(px: Float, py: Float, cx: Float, cy: Float, yawRad: Float): Float {
    val dx = px - cx; val dy = py - cy
    return cy + dx * sin(yawRad.toDouble()).toFloat() + dy * cos(yawRad.toDouble()).toFloat()
}

/**
 * Map a SCREEN-space pan delta back into FLAT (pre-rotation) offset space = R(−[yawRad])·pan, so a
 * single-finger drag moves content UNDER the finger at any orbit angle. The camera rotates flat→screen
 * by yaw, so a pan added directly to the flat offset feels rotated/inverted once the skyline is spun
 * (fully inverted at 180°, perpendicular at 90°). Un-rotating here is the whole direction fix; the
 * caller may additionally divide y by groundSquish(t) for 1:1 vertical feel (optional). Pure.
 */
fun flatPanDelta(panX: Float, panY: Float, yawRad: Float): MapPoint {
    val c = cos(yawRad.toDouble()).toFloat(); val s = sin(yawRad.toDouble()).toFloat()
    return MapPoint(panX * c + panY * s, -panX * s + panY * c)
}

// ─────────────────────────────────────────────────────────────────────────────
// Cities — the registry of places we have a real-OSM basemap for. Single source of truth for
// point-in-bbox routing, so the ViewModel can route a fix to a city WITHOUT reading the asset.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A city with a bundled basemap. [slug] selects assets/citymap-<slug>.json; [center]/[bounds] MUST
 * match that asset's "center"/"bbox" (kept here as constants so routing needs no asset IO).
 */
data class MappedCity(
    val slug: String,
    val displayName: String,
    val center: GeoPoint,
    val bounds: GeoBounds,
)

object Cities {
    // NB: each city's center/bounds MUST mirror its citymap-<slug>.json "center"/"bbox" (and the bboxes
    // in tools/extract_citymap.py) — keep them in lock-step. The three bboxes don't overlap in longitude,
    // so point-in-bbox routing (firstOrNull) is unambiguous.
    val MADURAI = MappedCity(
        slug = "madurai",
        displayName = "Madurai",
        center = GeoPoint(9.9195, 78.1193),
        bounds = GeoBounds(south = 9.85, west = 78.06, north = 9.99, east = 78.20),
    )
    val CHENNAI = MappedCity(
        slug = "chennai",
        displayName = "Chennai",
        center = GeoPoint(13.0418, 80.2341),
        bounds = GeoBounds(south = 12.95, west = 80.15, north = 13.18, east = 80.32),
    )
    val BENGALURU = MappedCity(
        slug = "bengaluru",
        displayName = "Bengaluru",
        center = GeoPoint(12.9716, 77.5946),
        bounds = GeoBounds(south = 12.88, west = 77.52, north = 13.06, east = 77.72),
    )

    /** Every city with a shipped basemap (a `citymap-<slug>.json` asset). */
    val ALL: List<MappedCity> = listOf(MADURAI, CHENNAI, BENGALURU)

    /** The mapped city a fix falls inside, or null = "outside your mapped cities". First match wins. */
    fun cityFor(lat: Double, lng: Double): MappedCity? =
        ALL.firstOrNull { it.bounds.contains(lat, lng) }

    /** The mapped city for a [slug], or [MADURAI] as a safe default (slug always comes from [ALL]). */
    fun bySlug(slug: String): MappedCity = ALL.firstOrNull { it.slug == slug } ?: MADURAI
}
