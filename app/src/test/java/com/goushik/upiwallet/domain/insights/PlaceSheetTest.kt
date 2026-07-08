package com.goushik.upiwallet.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Host unit tests for the Place-sheet geometry: the locator's centred projection ([MapProjection.centeredAt])
 * and the spread-aware accuracy radius ([buildPlaceSheet]). The radius is the slice's honesty contract —
 * a tapped SCREEN cluster can merge payments spread well past 110 m, so the halo must grow to cover the
 * real spread rather than overstate precision.
 */
class PlaceSheetTest {

    private val center = GeoPoint(9.92, 78.12)
    private val M_PER_DEG = 111_320.0

    // ── centeredAt: the locator projection ─────────────────────────────────────

    @Test fun `centeredAt puts the centre at the canvas middle`() {
        val p = MapProjection.centeredAt(center, scalePxPerM = 0.4, width = 300f, height = 200f)
        val c = p.project(center.lat, center.lng)
        assertEquals(150f, c.x, 1e-2f)
        assertEquals(100f, c.y, 1e-2f)
    }

    @Test fun `a point 110 m north lands above the centre by 110 times scale (north-up)`() {
        val scale = 0.4
        val p = MapProjection.centeredAt(center, scalePxPerM = scale, width = 300f, height = 200f)
        val north = p.project(center.lat + 110.0 / M_PER_DEG, center.lng)   // +110 m north
        assertEquals("x unchanged due north", 150f, north.x, 1e-2f)
        assertEquals("y = h/2 − 110·scale", 100f - (110.0 * scale).toFloat(), north.y, 0.5f)
    }

    @Test fun `a point 110 m east lands right of centre by 110 times scale (east-right)`() {
        val scale = 0.4
        val p = MapProjection.centeredAt(center, scalePxPerM = scale, width = 300f, height = 200f)
        val mPerDegLng = M_PER_DEG * cos(Math.toRadians(center.lat))
        val east = p.project(center.lat, center.lng + 110.0 / mPerDegLng)   // +110 m east
        assertEquals("x = w/2 + 110·scale", 150f + (110.0 * scale).toFloat(), east.x, 0.5f)
        assertEquals("y unchanged due east", 100f, east.y, 1e-2f)
    }

    // ── buildPlaceSheet: the honest accuracy radius ────────────────────────────

    @Test fun `a single payment sits at its own coord with the ~110 m floor radius`() {
        val place = buildPlaceSheet(listOf(txn("a", 9.957, 78.136, 10_000, ts = 5)))
        assertEquals(9.957, place.centerLat, 1e-9)
        assertEquals(78.136, place.centerLng, 1e-9)
        assertEquals(PLACE_ROUND_M, place.accuracyM, 1e-6)   // spread 0 → the floor
        assertEquals(1, place.count)
        assertEquals(10_000L, place.totalPaise)
    }

    @Test fun `co-located payments keep the floor radius, sum, and newest-first order`() {
        val members = listOf(
            txn("old", 9.957, 78.136, 1_000, ts = 100),
            txn("new", 9.957, 78.136, 2_000, ts = 300),
            txn("mid", 9.957, 78.136, 3_000, ts = 200),
        )
        val place = buildPlaceSheet(members)
        assertEquals(PLACE_ROUND_M, place.accuracyM, 1e-6)        // zero spread → still ~110 m
        assertEquals(6_000L, place.totalPaise)
        assertEquals(listOf("new", "mid", "old"), place.members.map { it.id })   // newest first
    }

    @Test fun `a spread cluster grows the halo to cover it — never overstates precision`() {
        // Two payments 400 m apart (pure north-south); the centroid sits between them, so each is ~200 m
        // from it → the honest radius is 200 + 110 = 310 m, NOT a misleading ~110 m.
        val dLat = 400.0 / M_PER_DEG
        val place = buildPlaceSheet(
            listOf(
                txn("south", 9.950, 78.120, 1_000, ts = 1),
                txn("north", 9.950 + dLat, 78.120, 1_000, ts = 2),
            ),
        )
        assertEquals(9.950 + dLat / 2, place.centerLat, 1e-9)    // centroid is the midpoint
        assertEquals(310.0, place.accuracyM, 1.0)                 // ~200 m spread + the 110 m floor
        assertTrue("a spread place reads wider than the floor", place.accuracyM > PLACE_ROUND_M)
    }

    private fun txn(id: String, lat: Double, lng: Double, paise: Long, ts: Long) =
        MapTxn(id = id, lat = lat, lng = lng, amountPaise = paise, timestampEvent = ts, label = id, category = null)
}
