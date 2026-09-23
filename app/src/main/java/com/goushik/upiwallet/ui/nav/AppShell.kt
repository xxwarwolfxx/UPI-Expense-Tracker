package com.goushik.upiwallet.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.ui.add.AddTransactionScreen
import com.goushik.upiwallet.ui.alltxns.AllTransactionsScreen
import com.goushik.upiwallet.ui.alltxns.AllTransactionsViewModel
import com.goushik.upiwallet.ui.alltxns.AllTxnsFilters
import com.goushik.upiwallet.ui.alltxns.categoryDrillFilters
import com.goushik.upiwallet.ui.budget.BudgetsScreen
import com.goushik.upiwallet.ui.common.AuroraGlassBackground
import com.goushik.upiwallet.ui.detail.TransactionDetailScreen
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.ui.home.HomeScreen
import com.goushik.upiwallet.ui.insights.InsightsScreen
import com.goushik.upiwallet.ui.insights.InsightsView
import com.goushik.upiwallet.ui.review.ReviewScreen
import com.goushik.upiwallet.ui.review.ReviewViewModel
import com.goushik.upiwallet.ui.settings.AddAccountScreen
import com.goushik.upiwallet.ui.settings.EditProfileScreen
import com.goushik.upiwallet.ui.settings.LocationSettingsScreen
import com.goushik.upiwallet.ui.settings.SettingsScreen
import com.goushik.upiwallet.ui.settings.UpdateBalanceScreen
import com.goushik.upiwallet.ui.theme.LocalHazeState
import com.goushik.upiwallet.ui.theme.Motion
import com.goushik.upiwallet.ui.theme.rememberReduceMotion
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Full-screen children presented *over* the current tab (not new bottom-nav destinations) — the same
 * swap idiom as [TransactionDetailScreen]. Each is reachable from Settings; Update-balance is also
 * reachable from the Home hero's "↻ Update balance" pill. A Serializable enum, so it survives
 * rotation / process-death directly via `rememberSaveable`.
 */
private enum class Overlay { UPDATE_BALANCE, ADD_ACCOUNT, EDIT_PROFILE, ADD_TXN, LOCATION_SETTINGS, ALL_TXNS, BUDGETS, REMOVED }

/**
 * The post-onboarding host. A hand-rolled route holder (sealed [Route] + `rememberSaveable` + a
 * single [BackHandler]) — the whole "back stack" is: a non-Home tab returns to Home; Home lets the
 * system default (exit) run. The bottom nav overlays every tab; its Review dot reads the Review tab's
 * own state, held here, so it stays correct regardless of which tab is showing. Transaction detail + the
 * Settings sub-screens slot in as full-screen swaps over the current tab, each owning its own back handling.
 */
@Composable
fun AppShell() {
    val hazeState = rememberHazeState()
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Home) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    // Which window the Insights tab opens on. A Home stat-tile deep-link sets this before switching tab;
    // an enum is autoSaver-serializable (same as the Overlay holder above), so no custom Saver needed.
    var insightsPeriod by rememberSaveable { mutableStateOf(InsightsPeriod.MONTH) }
    // The Chart|Map view mode is hoisted HERE (not inside InsightsContent) so it survives the full-screen
    // detail swap below — opening a payment from the map place-sheet and pressing Back returns to Map.
    var insightsView by rememberSaveable { mutableStateOf(InsightsView.CHART) }
    // The All-transactions list's VM (Activity-scoped — the same instance the screen resolves). Every OPEN
    // resets it to the opener's filters HERE, in the click, so a returning visit never inherits last time's
    // search; coming back from a payment's detail is not an open, so the search survives that.
    val allTxnsVm: AllTransactionsViewModel = viewModel(factory = AllTransactionsViewModel.Factory)
    val reduceMotion = rememberReduceMotion()

    // The Review tab's VM (Activity-scoped, handed to the tab below), so the nav dot and the Review screen
    // are one computation: the dot lights for a "Did this go through?" card as well as a category question.
    val reviewVm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory)
    val reviewState = reviewVm.state.collectAsStateWithLifecycle()
    val hasReview by remember { derivedStateOf { reviewState.value.hasOpenQuestions } }

    // A non-Home tab returns to Home — suppressed while a full-screen child is up (each child owns back).
    BackHandler(enabled = route != Route.Home && detailId == null && overlay == null) { route = Route.Home }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            // The aurora canvas — the single Haze source every frosted surface above blurs.
            AuroraGlassBackground(Modifier.fillMaxSize().hazeSource(hazeState))
            // The app's first screen transitions (Phase B): tab↔tab cross-fades; a full-screen child
            // (detail / Settings sub-screen) slides up + fades over the tab, and back down on close.
            // We render from the target SNAPSHOT (not the live state) so the outgoing screen stays correct
            // mid-transition. Reduce-motion collapses every spec to an instant cut. The nav lives OUTSIDE
            // this block so it doesn't flicker on tab switches — it just fades when a child takes over.
            AnimatedContent(
                targetState = ShellTarget(detailId, overlay, route),
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { shellTransition(reduceMotion) },
                label = "shell",
            ) { target ->
                val d = target.detailId
                val o = target.overlay
                when {
                    d != null -> TransactionDetailScreen(txnId = d, onBack = { detailId = null })

                    o != null -> when (o) {
                        Overlay.UPDATE_BALANCE -> UpdateBalanceScreen(
                            onBack = { overlay = null },
                            onAddAccount = { overlay = Overlay.ADD_ACCOUNT },
                        )
                        Overlay.ADD_ACCOUNT -> AddAccountScreen(onBack = { overlay = null })
                        Overlay.EDIT_PROFILE -> EditProfileScreen(onBack = { overlay = null })
                        Overlay.ADD_TXN -> AddTransactionScreen(onBack = { overlay = null })
                        Overlay.LOCATION_SETTINGS -> LocationSettingsScreen(onBack = { overlay = null })
                        Overlay.BUDGETS -> BudgetsScreen(onBack = { overlay = null })
                        Overlay.REMOVED -> com.goushik.upiwallet.ui.removed.RemovedPaymentsScreen(
                            onBack = { overlay = null },
                            onOpenTransaction = { detailId = it },
                        )
                        Overlay.ALL_TXNS -> AllTransactionsScreen(
                            onBack = { overlay = null },
                            onOpenTransaction = { detailId = it },
                            vm = allTxnsVm,
                        )
                    }

                    else -> when (target.route) {
                        Route.Home -> HomeScreen(
                            onOpenTransaction = { detailId = it },
                            onUpdateBalance = { overlay = Overlay.UPDATE_BALANCE },
                            onInsightsDay = { insightsPeriod = InsightsPeriod.DAY; route = Route.Insights },
                            onInsightsWeek = { insightsPeriod = InsightsPeriod.WEEK; route = Route.Insights },
                            onInsightsMonth = { insightsPeriod = InsightsPeriod.MONTH; route = Route.Insights },
                            onViewAllRecent = { allTxnsVm.startFrom(AllTxnsFilters()); overlay = Overlay.ALL_TXNS },
                            onOpenBudgets = { overlay = Overlay.BUDGETS },
                        )
                        Route.Insights -> InsightsScreen(
                            initialPeriod = insightsPeriod,
                            view = insightsView,
                            onViewChange = { insightsView = it },
                            onOpenLocationSettings = { overlay = Overlay.LOCATION_SETTINGS },
                            onOpenTransaction = { detailId = it },
                            onOpenCategory = { cat, period, range ->
                                allTxnsVm.startFrom(categoryDrillFilters(cat, period, range))
                                overlay = Overlay.ALL_TXNS
                            },
                            onOpenReview = { route = Route.Review },
                        )
                        Route.Review -> ReviewScreen(
                            onOpenTransaction = { detailId = it },
                            onFixUpiIds = { overlay = Overlay.EDIT_PROFILE },
                            vm = reviewVm,
                        )
                        Route.Settings -> SettingsScreen(
                            onUpdateBalance = { overlay = Overlay.UPDATE_BALANCE },
                            onEditProfile = { overlay = Overlay.EDIT_PROFILE },
                            onOpenLocation = { overlay = Overlay.LOCATION_SETTINGS },
                            onOpenBudgets = { overlay = Overlay.BUDGETS },
                            onOpenRemoved = { overlay = Overlay.REMOVED },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = detailId == null && overlay == null,
                enter = fadeIn(tween(if (reduceMotion) 0 else Motion.BASE)),
                exit = fadeOut(tween(if (reduceMotion) 0 else Motion.BASE)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomNavBar(
                    current = route,
                    hasReview = hasReview,
                    onSelect = { route = it },
                    onAdd = { overlay = Overlay.ADD_TXN },
                )
            }
        }
    }
}

/** Snapshot of which screen the shell is showing — the [AnimatedContent] target key. Equality across
 *  these three fields decides whether (and how) a transition runs. */
private data class ShellTarget(val detailId: String?, val overlay: Overlay?, val route: Route)

/** Transition between two shell states: a full-screen child rises/sinks over the tab; tabs cross-fade.
 *  Reduce-motion collapses to an instant cut. */
private fun AnimatedContentTransitionScope<ShellTarget>.shellTransition(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val dur = Motion.BASE
    val easing = Motion.EasingStandard
    val initChild = initialState.detailId != null || initialState.overlay != null
    val targetChild = targetState.detailId != null || targetState.overlay != null
    return when {
        // opening a child: it rises + fades in over the (fading) tab beneath
        !initChild && targetChild ->
            (slideInVertically(tween(dur, easing = easing)) { it / 6 } + fadeIn(tween(dur))) togetherWith
                fadeOut(tween(dur))
        // closing a child: it sinks + fades out, the tab fades back in
        initChild && !targetChild ->
            fadeIn(tween(dur)) togetherWith
                (slideOutVertically(tween(dur, easing = easing)) { it / 6 } + fadeOut(tween(dur)))
        // tab↔tab (or child↔child): a calm cross-fade
        else -> fadeIn(tween(dur)) togetherWith fadeOut(tween(dur))
    }
}

/** Persists the selected tab across rotation / process-death via the route's simple name. */
private val RouteSaver: Saver<Route, String> = Saver(
    save = { it::class.simpleName ?: "Home" },
    restore = { name -> TopLevelRoutes.firstOrNull { it::class.simpleName == name } ?: Route.Home },
)
