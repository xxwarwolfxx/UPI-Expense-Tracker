package com.goushik.upiwallet.ui.nav

/**
 * Top-level destinations behind the bottom nav. Deliberately a flat, argument-less set — a
 * lightweight `when`-routed shell, not Navigation-Compose (the four tabs have no nested graph,
 * no args, no deep links). Transaction detail will later be a *child presented over* a tab, not
 * a new entry here, so this stays at four.
 */
sealed interface Route {
    data object Home : Route
    data object Insights : Route
    data object Review : Route
    data object Settings : Route
}

val TopLevelRoutes: List<Route> = listOf(Route.Home, Route.Insights, Route.Review, Route.Settings)
