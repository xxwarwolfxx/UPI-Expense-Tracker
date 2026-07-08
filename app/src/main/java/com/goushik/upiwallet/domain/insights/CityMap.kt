package com.goushik.upiwallet.domain.insights

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A parsed real-OSM basemap skeleton (one bundled `assets/citymap-<slug>.json`, extracted offline by
 * `tools/extract_citymap.py`). Coords are [lat, lng] degrees. Polylines are stored FLATTENED
 * ([lat,lng,lat,lng,…]) — leaner than boxed points for the ~1000 polylines a city carries, and the
 * Composable just steps them in twos through [MapProjection.project] when it traces the cached basemap.
 *
 * Parsing tolerates a missing layer (the extractor can lose one to an Overpass 504 and still ship the
 * others), so any of roads/water/labels may be empty without failing the load.
 */
data class CityMap(
    val city: String,
    val center: GeoPoint,
    val bounds: GeoBounds,
    val roads: List<DoubleArray>,
    val water: List<DoubleArray>,
    val labels: List<CityLabel>,
    val attribution: String,
)

/** A real OSM locality name anchored at a point — drawn as ghost text (and a soft "place pool"). */
data class CityLabel(val name: String, val lat: Double, val lng: Double)

/** The OSM locality nearest a coord (squared-degree distance — great-circle is overkill at city scale).
 *  Shared by the Insights map AND the transaction-detail Location section. Pure → host unit-testable. */
fun nearestLabel(labels: List<CityLabel>, lat: Double, lng: Double): String? =
    labels.minByOrNull { val dlat = it.lat - lat; val dlng = it.lng - lng; dlat * dlat + dlng * dlng }?.name

/** Overload over a whole [CityMap] (its [CityMap.labels]). */
fun nearestLabel(city: CityMap, lat: Double, lng: Double): String? = nearestLabel(city.labels, lat, lng)

object CityMapLoader {

    /** Read + parse `assets/citymap-<slug>.json`. Returns null on any IO/parse failure (fail-silent —
     *  the map then shows its "no basemap" path rather than crashing). */
    fun load(context: Context, slug: String): CityMap? = try {
        val json = context.assets.open("citymap-$slug.json").bufferedReader().use { it.readText() }
        parse(json)
    } catch (e: Exception) {
        null
    }

    /** Read ONLY the locality labels, skipping the heavy ~1000-polyline road/water parse — a cheap offline
     *  place-name lookup for the transaction-detail Location text (the full map loads only on expand). */
    fun loadLabels(context: Context, slug: String): List<CityLabel>? = try {
        val json = context.assets.open("citymap-$slug.json").bufferedReader().use { it.readText() }
        parseLabels(JSONObject(json).optJSONArray("labels"))
    } catch (e: Exception) {
        null
    }

    /** Pure parse of the asset JSON. Separated from IO so it can be exercised without a Context. */
    fun parse(json: String): CityMap {
        val o = JSONObject(json)
        val center = o.getJSONArray("center")        // [lat, lng]
        val bbox = o.getJSONArray("bbox")            // [south, west, north, east]
        return CityMap(
            city = o.optString("city", ""),
            center = GeoPoint(center.getDouble(0), center.getDouble(1)),
            bounds = GeoBounds(
                south = bbox.getDouble(0), west = bbox.getDouble(1),
                north = bbox.getDouble(2), east = bbox.getDouble(3),
            ),
            roads = parsePolylines(o.optJSONArray("roads")),
            water = parsePolylines(o.optJSONArray("water")),
            labels = parseLabels(o.optJSONArray("labels")),
            attribution = o.optString("attribution", "© OpenStreetMap contributors"),
        )
    }

    /** [ [[lat,lng],…], … ] → list of flattened [lat,lng,lat,lng,…] arrays. Drops degenerate (<2 pt). */
    private fun parsePolylines(arr: JSONArray?): List<DoubleArray> {
        if (arr == null) return emptyList()
        val out = ArrayList<DoubleArray>(arr.length())
        for (i in 0 until arr.length()) {
            val line = arr.optJSONArray(i) ?: continue
            val n = line.length()
            if (n < 2) continue
            val flat = DoubleArray(n * 2)
            for (j in 0 until n) {
                val pt = line.optJSONArray(j) ?: continue
                flat[j * 2] = pt.optDouble(0)
                flat[j * 2 + 1] = pt.optDouble(1)
            }
            out.add(flat)
        }
        return out
    }

    /** [ ["name", lat, lng], … ] → labels. */
    private fun parseLabels(arr: JSONArray?): List<CityLabel> {
        if (arr == null) return emptyList()
        val out = ArrayList<CityLabel>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONArray(i) ?: continue
            if (l.length() < 3) continue
            out.add(CityLabel(l.optString(0), l.optDouble(1), l.optDouble(2)))
        }
        return out
    }
}
