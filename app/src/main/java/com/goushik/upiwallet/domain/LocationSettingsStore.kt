package com.goushik.upiwallet.domain

import android.content.Context

/**
 * Opt-in flag for the Insights map's silent location capture (Slice C). Plain SharedPreferences —
 * a coordinate-capture toggle isn't a secret, so this mirrors the lightweight [HealthStore] shape.
 * OFF by default: until the user turns it on, no location is ever read or stored.
 */
class LocationSettingsStore(ctx: Context) {
    private val prefs =
        ctx.applicationContext.getSharedPreferences("location_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
}
