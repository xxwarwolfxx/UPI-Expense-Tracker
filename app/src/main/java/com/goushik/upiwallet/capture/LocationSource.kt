package com.goushik.upiwallet.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.goushik.upiwallet.di.ServiceLocator

/** A capture-time fix, rounded to ~3 dp (~110 m) — the privacy ceiling AND the neighborhood-accuracy match. */
data class RoundedFix(val lat: Double, val lng: Double)

/**
 * Capture-time location for the Insights map, gated three ways (any failing → null, silently):
 *  1. opt-in OFF (the default) — never read.
 *  2. ACCESS_FINE_LOCATION not granted — COARSE's ~2 km is useless for ~110 m pins (spike-verified).
 *  3. no cached fix that is both fresh (< [MAX_AGE_MS]) and accurate enough (≤ [MAX_ACCURACY_M]).
 *
 * Reads ONLY [LocationManager.getLastKnownLocation] — a cheap cached value, no GPS request, no battery
 * cost — across all providers, taking the freshest qualifying one. The Slice-B spike confirmed the a11y
 * service reads fine on the while-in-use FINE grant (it runs at FG_SERVICE importance), so NO
 * background-location permission is needed. The fix is rounded before it ever touches storage.
 */
object LocationSource {
    private const val MAX_AGE_MS = 5 * 60_000L     // older than this is "where I was earlier" — drop
    private const val MAX_ACCURACY_M = 500f        // a COARSE (~2 km) or junk fix — drop

    fun currentFix(ctx: Context): RoundedFix? {
        if (!ServiceLocator.locationSettings.enabled) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        val now = System.currentTimeMillis()
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            } ?: continue
            if (now - loc.time > MAX_AGE_MS) continue
            // Drop a fix whose accuracy we can't verify — an unaccuracy-tagged fix must not earn a pin.
            if (!loc.hasAccuracy() || loc.accuracy > MAX_ACCURACY_M) continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        val b = best ?: return null
        return RoundedFix(round3(b.latitude), round3(b.longitude))
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /**
     * A FRESH foreground fix for the map's "show me where I am now" button — **display-only**.
     *
     * Unlike [currentFix], this is NOT rounded and is NEVER written to Room: the privacy contract
     * (~110 m rounding, allowBackup=false, going-forward-only) is about *stored* coordinates; a precise
     * live "you are here" puck that only paints to the screen and is dropped on the next gesture does not
     * touch storage. FINE-gated (the caller must already hold the permission — the app is foreground when
     * the button is tapped, so while-in-use FINE suffices); fail-silent. Falls back to the freshest
     * last-known fix if the active request yields nothing.
     */
    fun requestLive(ctx: Context, onResult: (lat: Double, lng: Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val provider = pickProvider(lm)
        try {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                val l = loc ?: freshestLastKnown(lm)
                if (l != null) onResult(l.latitude, l.longitude)
            }
        } catch (e: Exception) {
            freshestLastKnown(lm)?.let { onResult(it.latitude, it.longitude) }
        }
    }

    private fun pickProvider(lm: LocationManager): String = try {
        when {
            Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
    } catch (e: Exception) {
        LocationManager.NETWORK_PROVIDER
    }

    private fun freshestLastKnown(lm: LocationManager): Location? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            } ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }
}
