package com.goushik.upiwallet.domain.insights

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host unit tests for the PURE map geometry ([MapProjection], clustering, [Cities] routing) and for the
 * reconciliation guarantee the advisor flagged: the map's "N of M payments have a location" footnote
 * must use the SAME window + predicate as the chart's payment count, so M can never disagree.
 */
class MapAndWindowTest {

    private val madurai = Cities.MADURAI

    // ── projection ──────────────────────────────────────────────────────────

    @Test fun `bbox midpoint maps to canvas centre, and the city centre projects on-canvas`() {
        val p = MapProjection.fit(madurai.center, madurai.bounds, width = 400f, height = 600f)
        // The centring invariant is about the BBOX midpoint (the city centre is offset within the bbox).
        val midLat = (madurai.bounds.south + madurai.bounds.north) / 2
        val midLng = (madurai.bounds.west + madurai.bounds.east) / 2
        val mid = p.project(midLat, midLng)
        assertEquals(200f, mid.x, 2f)
        assertEquals(300f, mid.y, 2f)
        // The city centre still lands inside the canvas (it's just west-of-midpoint here).
        val c = p.project(madurai.center.lat, madurai.center.lng)
        assertTrue(c.x in 0f..400f && c.y in 0f..600f)
    }

    @Test fun `the real Madurai pin lands north-east of centre`() {
        val p = MapProjection.fit(madurai.center, madurai.bounds, width = 400f, height = 600f)
        val c = p.project(madurai.center.lat, madurai.center.lng)
        val pin = p.project(9.957, 78.136)        // the real device pin: higher lat (N), higher lng (E)
        assertTrue("pin should be above centre (north → smaller y)", pin.y < c.y)
        assertTrue("pin should be right of centre (east → larger x)", pin.x > c.x)
    }

    @Test fun `north edge maps above south edge, west edge left of east edge`() {
        val p = MapProjection.fit(madurai.center, madurai.bounds, width = 400f, height = 600f)
        val north = p.project(madurai.bounds.north, madurai.center.lng)
        val south = p.project(madurai.bounds.south, madurai.center.lng)
        val west = p.project(madurai.center.lat, madurai.bounds.west)
        val east = p.project(madurai.center.lat, madurai.bounds.east)
        assertTrue(north.y < south.y)            // north is up
        assertTrue(west.x < east.x)              // east is right
    }

    @Test fun `scale is uniform — equal px per metre on both axes (aspect preserved)`() {
        val p = MapProjection.fit(madurai.center, madurai.bounds, width = 400f, height = 600f)
        val c = p.project(madurai.center.lat, madurai.center.lng)
        // +0.01° lat ≈ +1113 m north; +0.01° lng ≈ +1097 m east at this latitude. px/m must match.
        val north = p.project(madurai.center.lat + 0.01, madurai.center.lng)
        val east = p.project(madurai.center.lat, madurai.center.lng + 0.01)
        val pxPerMetreLat = (c.y - north.y) / (0.01 * 111_320.0)
        val pxPerMetreLng = (east.x - c.x) / (0.01 * 111_320.0 * Math.cos(Math.toRadians(madurai.center.lat)))
        assertEquals(pxPerMetreLat, pxPerMetreLng, 1e-3)
    }

    // ── clustering ──────────────────────────────────────────────────────────

    @Test fun `far points stay separate, near points merge into one well`() {
        val xs = floatArrayOf(10f, 12f, 300f)
        val ys = floatArrayOf(10f, 14f, 280f)
        val amt = longArrayOf(100, 200, 5000)
        val t = longArrayOf(1000, 3000, 2000)
        val clusters = clusterScreenPoints(xs, ys, amt, t, radiusPx = 30f)
        assertEquals(2, clusters.size)
        val well = clusters.first { it.count > 1 }
        assertEquals(2, well.count)
        assertEquals(300L, well.sumPaise)
        assertEquals(3000L, well.latestMs)       // most-recent member wins the recency hue
        assertEquals(listOf(0, 1), well.memberIndices)
    }

    @Test fun `empty input yields no clusters`() {
        assertTrue(clusterScreenPoints(FloatArray(0), FloatArray(0), LongArray(0), LongArray(0), 20f).isEmpty())
    }

    // ── pan/zoom view transform ───────────────────────────────────────────────

    @Test fun `identity view leaves base points untouched`() {
        val v = MapView.Identity
        val p = v.apply(123f, 456f)
        assertEquals(123f, p.x, 0f)
        assertEquals(456f, p.y, 0f)
    }

    @Test fun `pinch keeps the base point under the centroid fixed`() {
        val v = MapView.Identity
        val cx = 200f; val cy = 300f
        // The base point currently under the centroid:
        val before = v.apply(200f, 300f)          // == (200,300) at identity
        val zoomed = v.onGesture(cx, cy, panX = 0f, panY = 0f, zoomChange = 2f, minZoom = 1f, maxZoom = 6f)
        assertEquals(2f, zoomed.zoom, 1e-4f)
        val after = zoomed.apply(200f, 300f)       // same base point
        assertEquals("x under the centroid stays put", before.x, after.x, 1e-3f)
        assertEquals("y under the centroid stays put", before.y, after.y, 1e-3f)
    }

    @Test fun `zoom is clamped to the allowed range`() {
        val v = MapView.Identity.onGesture(0f, 0f, 0f, 0f, zoomChange = 100f, minZoom = 1f, maxZoom = 6f)
        assertEquals(6f, v.zoom, 0f)
        val z = MapView(6f, 0f, 0f).onGesture(0f, 0f, 0f, 0f, zoomChange = 0.01f, minZoom = 1f, maxZoom = 6f)
        assertEquals(1f, z.zoom, 0f)
    }

    @Test fun `clampPan pins offset to zero at zoom 1 and bounds it when zoomed`() {
        // At zoom 1 you cannot pan — the city exactly fills the viewport.
        val flat = MapView(1f, 80f, -50f).clampPan(400f, 600f)
        assertEquals(0f, flat.offsetX, 0f)
        assertEquals(0f, flat.offsetY, 0f)
        // At zoom 2 the offset is bounded to [w*(1-z), 0] = [-400, 0] so content never leaves the canvas.
        val zoomed = MapView(2f, 999f, -999f).clampPan(400f, 600f)
        assertEquals(0f, zoomed.offsetX, 0f)         // clamped up to 0
        assertEquals(-600f, zoomed.offsetY, 0f)      // clamped down to h*(1-z) = -600
    }

    @Test fun `centerOn places a central base point at the canvas centre`() {
        // A central base point CAN be centred; an edge point can't (clampPan stops void), which is correct.
        val v = MapView.centerOn(bx = 200f, by = 300f, w = 400f, h = 600f, targetZoom = 3f)
        val p = v.apply(200f, 300f)
        assertEquals(200f, p.x, 1f)
        assertEquals(300f, p.y, 1f)
    }

    // ── tilt (3D skyline camera) ───────────────────────────────────────────────

    @Test fun `tilt t=0 is identity`() {
        assertEquals(1f, groundSquish(0f), 0f)
        assertEquals(123f, tiltY(123f, 330f, 0f), 1e-4f)
        assertEquals(500f, tiltY(500f, 330f, 0f), 1e-4f)
    }

    @Test fun `the pivot row is a fixed point at any tilt`() {
        assertEquals(330f, tiltY(330f, 330f, 0.5f), 1e-4f)
        assertEquals(330f, tiltY(330f, 330f, 1f), 1e-4f)
    }

    @Test fun `full tilt compresses y toward the pivot and preserves order`() {
        assertEquals(0.55f, groundSquish(1f), 1e-4f)
        val pivot = 600f
        // y' = s*y + (1-s)*pivot, s=0.55 → top (0) and bottom (1200) move toward 600, order kept.
        val top = tiltY(0f, pivot, 1f)        // 0.55*0 + 0.45*600 = 270
        val bottom = tiltY(1200f, pivot, 1f)  // 0.55*1200 + 0.45*600 = 660+270 = 930
        assertEquals(270f, top, 1e-3f)
        assertEquals(930f, bottom, 1e-3f)
        assertTrue("y-order preserved (painter's sort stays valid)", top < pivot && pivot < bottom)
    }

    // ── rotatable axonometric camera (yaw + pitch) ─────────────────────────────

    @Test fun `groundTransform is identity at yaw 0 pitch 0`() {
        val p = groundTransform(123f, 456f, cx = 200f, cy = 300f, pivotY = 330f, yawRad = 0f, t = 0f)
        assertEquals(123f, p.x, 1e-3f)
        assertEquals(456f, p.y, 1e-3f)
    }

    @Test fun `groundTransform with no yaw equals pure tilt`() {
        val p = groundTransform(123f, 456f, cx = 200f, cy = 300f, pivotY = 330f, yawRad = 0f, t = 1f)
        assertEquals(123f, p.x, 1e-3f)                       // x untouched
        assertEquals(tiltY(456f, 330f, 1f), p.y, 1e-3f)      // y == the y-squish
    }

    @Test fun `the rotation centre is a fixed point`() {
        val p = groundTransform(200f, 300f, cx = 200f, cy = 300f, pivotY = 330f, yawRad = 1.1f, t = 0.7f)
        assertEquals(200f, p.x, 1e-3f)
        assertEquals(tiltY(300f, 330f, 0.7f), p.y, 1e-3f)
    }

    @Test fun `a quarter-turn yaw maps east of centre to due south (flat)`() {
        // yaw = +90°, pitch 0 (flat so we read the rotation alone). East point (cx+10, cy).
        val p = groundTransform(210f, 300f, cx = 200f, cy = 300f, pivotY = 330f, yawRad = (Math.PI / 2).toFloat(), t = 0f)
        assertEquals(200f, p.x, 1e-2f)        // x → centre
        assertEquals(310f, p.y, 1e-2f)        // y → centre + 10 (south / down-screen)
    }

    @Test fun `painter depth flips between yaw 0 and 180`() {
        // A point "in front" (south, larger y) is near at yaw 0, far at yaw 180.
        val south = 400f
        val d0 = groundDepth(200f, south, cx = 200f, cy = 300f, yawRad = 0f)
        val d180 = groundDepth(200f, south, cx = 200f, cy = 300f, yawRad = Math.PI.toFloat())
        assertEquals(400f, d0, 1e-2f)         // unchanged at yaw 0
        assertEquals(200f, d180, 1e-2f)       // mirrored about the centre at 180°
        assertTrue("front/back swap under a half-turn", d0 > d180)
    }

    // ── pan-delta un-rotation (the "inverted pan when rotated" fix) ─────────────

    // forward camera rotation (matches groundTransform): screen_dir = R(yaw)·flat
    private fun fwd(dx: Float, dy: Float, yaw: Float): Pair<Float, Float> {
        val c = Math.cos(yaw.toDouble()).toFloat(); val s = Math.sin(yaw.toDouble()).toFloat()
        return (dx * c - dy * s) to (dx * s + dy * c)
    }

    @Test fun `flatPanDelta is identity at yaw 0`() {
        val d = flatPanDelta(10f, 5f, 0f)
        assertEquals(10f, d.x, 1e-4f); assertEquals(5f, d.y, 1e-4f)
    }

    @Test fun `a finger-drag survives the camera rotation at every yaw (round-trip)`() {
        // The whole point: rotating the un-rotated delta forward must recover the finger delta — so
        // content moves under the finger regardless of orbit. Tests the SIGN (the trap) at 90° + 180°.
        for (deg in intArrayOf(0, 90, 180, 270, 37)) {
            val yaw = Math.toRadians(deg.toDouble()).toFloat()
            val d = flatPanDelta(10f, 0f, yaw)              // finger drags +x (right)
            val (sx, sy) = fwd(d.x, d.y, yaw)               // what the camera renders that flat delta as
            assertEquals("x recovered at $deg°", 10f, sx, 1e-2f)
            assertEquals("y recovered at $deg°", 0f, sy, 1e-2f)
        }
    }

    @Test fun `at yaw 90 a rightward drag becomes a flat -y delta (sign check)`() {
        val d = flatPanDelta(10f, 0f, (Math.PI / 2).toFloat())
        assertEquals(0f, d.x, 1e-2f)
        assertEquals(-10f, d.y, 1e-2f)   // NOT +10 — the sign that a 180° device test can't disambiguate
    }

    // ── city routing ─────────────────────────────────────────────────────────

    @Test fun `each city bbox routes its own coords, everywhere else is outside (null)`() {
        assertEquals(Cities.MADURAI, Cities.cityFor(9.957, 78.136))     // the real Madurai pin
        assertEquals(Cities.CHENNAI, Cities.cityFor(13.06, 80.24))      // Chennai (Adyar-ish)
        assertEquals(Cities.BENGALURU, Cities.cityFor(12.97, 77.59))    // Bengaluru (centre)
        assertNotNull(Cities.cityFor(9.92, 78.12))                      // Madurai core
        assertNull("Delhi is in none of the three bboxes", Cities.cityFor(28.61, 77.20))
        assertNull("a sea point east of Chennai is outside", Cities.cityFor(13.05, 80.40))
        assertEquals("bySlug round-trips", Cities.CHENNAI, Cities.bySlug("chennai"))
    }

    // ── reconciliation: the footnote's M == the chart's txnCount ───────────────

    @Test fun `map denominator equals chart payment count over the same window`() {
        val now = 1_717_200_000_000L                 // 2024-06-01-ish; period derives the window
        val win = spendWindow(InsightsPeriod.MONTH, now)
        val inWindow = win.startMs + 24L * 60 * 60 * 1000   // a day into the window
        val ownVpas = setOf("bram@oksbi")
        val ownNames = setOf("bram")

        val txns = listOf(
            debit(1, 10_000, inWindow),                          // counts
            debit(2, 20_000, inWindow, lat = 9.95, lng = 78.13), // counts (located)
            debit(3, 30_000, win.startMs),                       // counts (start is INCLUSIVE)
            debit(4, 40_000, win.endMs - 1),                     // counts (just inside the end)
            debit(5, 50_000, win.endMs),                         // EXCLUDED (end is exclusive)
            debit(6, 60_000, win.startMs - 1),                   // EXCLUDED (before start)
            credit(7, 70_000, inWindow),                         // EXCLUDED (not a debit)
            debit(8, 80_000, inWindow, status = TxnStatus.DISCARDED), // EXCLUDED
            debit(9, 90_000, inWindow, payeeVpa = "bram@oksbi"),   // EXCLUDED (self-transfer)
        )

        val chart = bucketSpend(txns, ownVpas, ownNames, InsightsPeriod.MONTH, now)
        val mapM = txns.count { isSpend(it, ownVpas, ownNames) && win.contains(it.timestampEvent) }
        assertEquals("footnote denominator must equal chart txnCount", chart.txnCount, mapM)
        assertEquals(4, mapM)

        // Roster identity: located + noLocation == M; located = txns 1..4 with a coord (only #2).
        val located = txns.count { isSpend(it, ownVpas, ownNames) && win.contains(it.timestampEvent) && it.latRounded != null }
        val noLocation = mapM - located
        assertEquals(1, located)
        assertEquals(3, noLocation)
        assertEquals(mapM, located + noLocation)
    }

    // ── multi-city: per-city tallies + the "outside = all cities" identity ─────

    @Test fun `buildMapData tallies each city, routes the rest to outside, and reconciles`() {
        val now = 1_717_200_000_000L
        val win = spendWindow(InsightsPeriod.MONTH, now)
        val t = win.startMs + 3_600_000L
        val txns = listOf(
            debit(1, 10_000, t, lat = 9.95, lng = 78.13),    // Madurai
            debit(2, 20_000, t, lat = 9.96, lng = 78.14),    // Madurai
            debit(3, 30_000, t, lat = 13.06, lng = 80.24),   // Chennai
            debit(4, 40_000, t, lat = 12.97, lng = 77.59),   // Bengaluru
            debit(5, 50_000, t, lat = 19.07, lng = 72.87),   // Mumbai → outside all mapped bboxes
            debit(6, 60_000, t),                             // no coords → no-location
            debit(7, 70_000, t),                             // no coords → no-location
        )
        val m = buildMapData(txns, emptySet(), emptySet(), InsightsPeriod.MONTH, now, locationEnabled = true)

        assertEquals(7, m.totalSpendCount)
        assertEquals(5, m.locatedCount)
        val byCity = m.cityTallies.associateBy { it.city.slug }
        assertEquals(2, byCity.getValue("madurai").count)
        assertEquals(30_000L, byCity.getValue("madurai").sumPaise)
        assertEquals(1, byCity.getValue("chennai").count)
        assertEquals(1, byCity.getValue("bengaluru").count)
        assertEquals("Mumbai is outside every mapped bbox", 1, m.outsideCount)
        assertEquals(50_000L, m.outsideSumPaise)
        assertEquals(2, m.noLocationCount)
        assertEquals("the most-populated city shows first", "madurai", m.defaultCitySlug)
        assertEquals(2, m.plottedFor("madurai").size)
        assertEquals(1, m.plottedFor("chennai").size)
        assertTrue(m.hasAnyPlotted)

        // THE reconciliation identity the footnote + roster promise:
        val mapped = m.cityTallies.sumOf { it.count }
        assertEquals(m.totalSpendCount, mapped + m.outsideCount + m.noLocationCount)
    }

    @Test fun `all-outside spend reports no mapped pins but still reconciles`() {
        val now = 1_717_200_000_000L
        val t = spendWindow(InsightsPeriod.MONTH, now).startMs + 3_600_000L
        val m = buildMapData(
            listOf(debit(1, 10_000, t, lat = 19.07, lng = 72.87)),   // Mumbai only
            emptySet(), emptySet(), InsightsPeriod.MONTH, now, locationEnabled = true,
        )
        assertEquals(false, m.hasAnyPlotted)               // → the "Outside your mapped cities" empty state
        assertEquals(1, m.outsideCount)
        assertEquals(0, m.cityTallies.sumOf { it.count })
        assertEquals(1, m.totalSpendCount)
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun debit(
        n: Int, paise: Long, ts: Long,
        status: TxnStatus = TxnStatus.CONFIRMED,
        payeeVpa: String? = null,
        lat: Double? = null, lng: Double? = null,
    ) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.DEBIT, status = status,
        payeeVpa = payeeVpa, timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
        latRounded = lat, lngRounded = lng,
    )

    private fun credit(n: Int, paise: Long, ts: Long) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )
}
