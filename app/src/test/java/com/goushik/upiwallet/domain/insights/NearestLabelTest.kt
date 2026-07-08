package com.goushik.upiwallet.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PURE host tests for [nearestLabel] — the offline OSM-locality lookup shared by the Insights map and the
 * transaction-detail Location row. Nearest by squared-degree distance; null when there are no labels.
 */
class NearestLabelTest {

    private val labels = listOf(
        CityLabel("Arappalayam", 9.9234, 78.1187),
        CityLabel("Othakadai", 9.9187, 78.1352),
        CityLabel("Tirupparankundram", 9.9016, 78.1287),
    )

    @Test fun `picks the closest locality to a fix`() {
        // A point a hair north-east of Arappalayam should resolve to it.
        assertEquals("Arappalayam", nearestLabel(labels, 9.9240, 78.1190))
    }

    @Test fun `a fix beside Othakadai resolves to Othakadai`() {
        assertEquals("Othakadai", nearestLabel(labels, 9.9185, 78.1350))
    }

    @Test fun `no labels yields null`() {
        assertNull(nearestLabel(emptyList(), 9.92, 78.12))
    }

    @Test fun `the CityMap overload delegates to its labels`() {
        val city = CityMap(
            city = "Madurai",
            center = GeoPoint(9.9195, 78.1193),
            bounds = GeoBounds(south = 9.85, west = 78.06, north = 9.99, east = 78.20),
            roads = emptyList(),
            water = emptyList(),
            labels = labels,
            attribution = "© OpenStreetMap contributors",
        )
        assertEquals("Tirupparankundram", nearestLabel(city, 9.9010, 78.1290))
    }
}
