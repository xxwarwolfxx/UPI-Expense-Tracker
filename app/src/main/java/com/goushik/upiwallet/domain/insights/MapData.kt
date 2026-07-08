package com.goushik.upiwallet.domain.insights

import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.util.prettyName
import kotlin.math.cos
import kotlin.math.hypot

/** One located spend, ready to pin. Position is the rounded (~110 m) capture-time fix, not the shop. */
data class MapTxn(
    val id: String,
    val lat: Double,
    val lng: Double,
    val amountPaise: Long,
    val timestampEvent: Long,
    val label: String,        // payee name, else VPA, else a generic — for the pin tooltip + list row
    val category: String?,    // drives the colored dot in the paired list (NOT shown on the map face)
)

/** One mapped city's located-spend tally for the window — a city-switcher / roster row. */
data class CityTally(val city: MappedCity, val count: Int, val sumPaise: Long)

/**
 * The map's slice of Insights state. Built by the PURE [buildMapData] over the SAME window + spend
 * predicate as the chart ([spendWindow] + [isSpend]). Located spend is routed to EVERY mapped city
 * (point-in-bbox) so the city switcher can pick any of them without a VM round-trip, and "outside"
 * means outside ALL mapped bboxes (not just the shown city). The reconciliation identity always holds:
 *
 *   Σ cityTallies.count + outsideCount + noLocationCount == totalSpendCount   (== the chart's txnCount)
 *
 * — which is exactly what the footnote ("N of M payments have a location") + the roster promise.
 */
data class MapData(
    val locationEnabled: Boolean,                 // opt-in flag — drives "turn it on" vs "no data yet"
    val defaultCitySlug: String,                  // auto-pick (most located) — the switcher's initial value
    val plottedByCity: Map<String, List<MapTxn>>, // slug → located spend inside that city's bbox (this window)
    val cityTallies: List<CityTally>,             // every mapped city (incl. zero-count) — switcher/roster rows
    val totalSpendCount: Int,                     // M — every spend in the window (located or not)
    val locatedCount: Int,                        // spend with a coordinate (any mapped city + outside)
    val outsideCount: Int,                        // located, but outside ALL mapped bboxes
    val outsideSumPaise: Long,                    // ₹ of those outside payments (the roster's Outside row)
    val noLocationCount: Int,                     // spend with no coordinate
) {
    /** Located spend inside [slug] (empty for an unknown slug or a city with none this window). */
    fun plottedFor(slug: String): List<MapTxn> = plottedByCity[slug] ?: emptyList()

    /** True once any mapped city has a pin — drives the GLOBAL "no places" empty state (so switching to
     *  an empty city still shows the map + the switcher rather than a dead-end empty screen). */
    val hasAnyPlotted: Boolean get() = cityTallies.any { it.count > 0 }

    companion object {
        /** Empty default while Room hasn't emitted — opt-in unknown defaults OFF, no payments. */
        val Empty = MapData(
            locationEnabled = false,
            defaultCitySlug = Cities.MADURAI.slug,
            plottedByCity = emptyMap(),
            cityTallies = Cities.ALL.map { CityTally(it, 0, 0L) },
            totalSpendCount = 0,
            locatedCount = 0,
            outsideCount = 0,
            outsideSumPaise = 0L,
            noLocationCount = 0,
        )
    }
}

/**
 * PURE — peer of [bucketSpend]. Filters to spend-in-window (the shared predicate + window), splits into
 * located/not, routes EVERY located fix to its mapped city by point-in-bbox (or "outside" = no mapped
 * bbox), tallies each city, and picks the most-populated city as the default to show. No projection
 * here (that needs the canvas size — it lives in the UI).
 */
fun buildMapData(
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    period: InsightsPeriod,
    nowMs: Long,
    locationEnabled: Boolean,
    customStartMs: Long? = null,
    customEndMs: Long? = null,
): MapData {
    val win = spendWindow(period, nowMs, customStartMs, customEndMs)
    val spendInWindow = txns.filter { isSpend(it, ownVpas, ownNames) && win.contains(it.timestampEvent) }
    val total = spendInWindow.size

    val located = spendInWindow.filter { it.latRounded != null && it.lngRounded != null }
    // Route each located fix to its mapped city (the keys are the Cities.ALL instances) or null = outside.
    val byCity = located.groupBy { Cities.cityFor(it.latRounded!!, it.lngRounded!!) }
    val plottedByCity = Cities.ALL.associate { c -> c.slug to (byCity[c]?.map { it.toMapTxn() } ?: emptyList()) }
    val cityTallies = Cities.ALL.map { c ->
        val ps = plottedByCity.getValue(c.slug)
        CityTally(c, ps.size, ps.sumOf { it.amountPaise })
    }
    val outside = byCity[null] ?: emptyList()
    // Default to the most-populated mapped city; if none has a pin, fall back to the default bundled city.
    val defaultCity = cityTallies.maxByOrNull { it.count }?.takeIf { it.count > 0 }?.city ?: Cities.MADURAI

    return MapData(
        locationEnabled = locationEnabled,
        defaultCitySlug = defaultCity.slug,
        plottedByCity = plottedByCity,
        cityTallies = cityTallies,
        totalSpendCount = total,
        locatedCount = located.size,
        outsideCount = outside.size,
        outsideSumPaise = outside.sumOf { it.amountPaise },
        noLocationCount = total - located.size,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Place sheet — the "where you were" object behind a tapped pin/well.
// ─────────────────────────────────────────────────────────────────────────────

/** The ~110 m coordinate-rounding floor (3 dp ≈ 110 m). The accuracy halo never reads tighter than this. */
const val PLACE_ROUND_M = 110.0

/**
 * The Place-sheet's data for one tapped spot: the payments there + an HONEST accuracy radius. A tapped
 * cluster is a SCREEN cluster, so its members can be spread out (at the city-fit zoom a 26 dp cluster
 * radius is ~1 km on the ground) — a fixed "~110 m" halo would then overstate precision, the exact
 * dishonesty this slice's reconciliation work fought. So the halo grows to cover the real spread:
 * [accuracyM] = max(PLACE_ROUND_M, farthest member from the centroid + PLACE_ROUND_M). The locator
 * sizes itself to this; the caption reports it. PURE → host unit-testable.
 */
data class PlaceSheet(
    val centerLat: Double,
    val centerLng: Double,
    val accuracyM: Double,         // the honest "where you were" radius (>= the ~110 m rounding floor)
    val totalPaise: Long,
    val members: List<MapTxn>,     // newest first
) {
    val count: Int get() = members.size
}

/**
 * PURE — the Place-sheet for a set of co-located payments (a tapped cluster's members): geographic
 * centroid, spread-aware accuracy radius, total, and members newest-first. Equirectangular metres about
 * the centroid (great-circle is overkill at city scale). Requires at least one member.
 */
fun buildPlaceSheet(members: List<MapTxn>): PlaceSheet {
    require(members.isNotEmpty()) { "a place has at least one payment" }
    val lat0 = members.sumOf { it.lat } / members.size
    val lng0 = members.sumOf { it.lng } / members.size
    val mPerDegLng = 111_320.0 * cos(Math.toRadians(lat0))   // 111_320 m/deg lat; lng scales by cos(lat)
    val spreadM = members.maxOf { m ->
        hypot((m.lng - lng0) * mPerDegLng, (m.lat - lat0) * 111_320.0)
    }
    return PlaceSheet(
        centerLat = lat0,
        centerLng = lng0,
        accuracyM = maxOf(PLACE_ROUND_M, spreadM + PLACE_ROUND_M),
        totalPaise = members.sumOf { it.amountPaise },
        members = members.sortedByDescending { it.timestampEvent },
    )
}

private fun TransactionEntity.toMapTxn(): MapTxn = MapTxn(
    id = id,
    lat = latRounded!!,
    lng = lngRounded!!,
    amountPaise = amountPaise,
    timestampEvent = timestampEvent,
    label = prettyName(payeeName)
        ?: payeeVpa?.substringBefore('@')?.ifEmpty { null }
        ?: "Payment",
    category = category,
)
