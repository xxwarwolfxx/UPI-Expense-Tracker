package com.goushik.upiwallet.domain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-feel preferences. "Fancy card" = the hero card's physical-card theatrics — 3D tilt, moving shine,
 * embossed digits, drop shadow, and the 180° face flip. Plain SharedPreferences (cosmetic, not a secret —
 * mirrors [LocationSettingsStore]), fronted by a [StateFlow] so the Home hero restyles the instant the
 * Settings switch is thrown (SharedPreferences alone isn't observable). ON by default — the fancy card is
 * the app's signature; OFF = a calm static glass card.
 */
class UiPrefsStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val fancyCardState = MutableStateFlow(prefs.getBoolean(KEY_FANCY_CARD, true))
    val fancyCard: StateFlow<Boolean> = fancyCardState.asStateFlow()

    fun setFancyCard(value: Boolean) {
        prefs.edit().putBoolean(KEY_FANCY_CARD, value).apply()
        fancyCardState.value = value
    }

    // Budget alerts (Phase 2) — the app's only user-facing notification, so OFF by default (opt-in, like
    // AI + location). Fronted by a StateFlow so the Budgets screen toggle reacts instantly.
    private val budgetAlertsState = MutableStateFlow(prefs.getBoolean(KEY_BUDGET_ALERTS, false))
    val budgetAlerts: StateFlow<Boolean> = budgetAlertsState.asStateFlow()

    fun setBudgetAlerts(value: Boolean) {
        prefs.edit().putBoolean(KEY_BUDGET_ALERTS, value).apply()
        budgetAlertsState.value = value
    }

    // Donations (Phase 3) — a COUNT, not just a boolean, so the donate button can ALTERNATE the character's
    // hat on every tip (odd count = hat off / waving, even = hat back on). Bumped optimistically when the user
    // returns from the UPI app (deep-links report success unreliably); a forgeable cosmetic counter, which is
    // fine for a free single-user sideload. Seeded from the old `has_donated` boolean so prior donors keep
    // hat-off. Fronted by a StateFlow so the button re-renders the instant it changes.
    private val donationCountState = MutableStateFlow(
        prefs.getInt(KEY_DONATION_COUNT, if (prefs.getBoolean(KEY_DONATED, false)) 1 else 0),
    )
    val donationCount: StateFlow<Int> = donationCountState.asStateFlow()

    /** Record one tip: bumps the persisted count (which flips the hat and unlocks the "tipped before" copy). */
    fun incrementDonations() {
        val next = donationCountState.value + 1
        prefs.edit().putInt(KEY_DONATION_COUNT, next).apply()
        donationCountState.value = next
    }

    companion object {
        private const val KEY_FANCY_CARD = "fancy_card"
        private const val KEY_BUDGET_ALERTS = "budget_alerts"
        private const val KEY_DONATED = "has_donated" // legacy boolean — read only to seed the count below
        private const val KEY_DONATION_COUNT = "donation_count"
    }
}
