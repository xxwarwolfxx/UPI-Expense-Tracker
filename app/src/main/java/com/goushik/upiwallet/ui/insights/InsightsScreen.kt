package com.goushik.upiwallet.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.domain.insights.ChartPoint
import com.goushik.upiwallet.domain.insights.InsightsData
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.PickerDate
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Money
import java.time.format.DateTimeFormatter

/**
 * Insights tab (USER-FLOW: "where did it go?"). Collects [InsightsViewModel], owns the Custom Start/End
 * date pickers + the deep-link [LaunchedEffect], and delegates the (testable, previewable) layout to
 * [InsightsContent]. Sits on AppShell's aurora hazeSource — no WalletBackground here. The chart is given
 * the remaining vertical space (weight) so it fills the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    initialPeriod: InsightsPeriod = InsightsPeriod.MONTH,
    view: InsightsView = InsightsView.CHART,
    onViewChange: (InsightsView) -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
    /** A donut slice was tapped: its category label + the window the slice was summed over (the period,
     *  and the applied custom range when the period is CUSTOM) so the list it opens adds up to the slice. */
    onOpenCategory: (label: String, period: InsightsPeriod, customRange: Pair<Long, Long>?) -> Unit = { _, _, _ -> },
    onOpenReview: () -> Unit = {},
    vm: InsightsViewModel = viewModel(factory = InsightsViewModel.Factory),
) {
    // Deep-link entry (e.g. a Home stat tile → "This month") seeds the active window once.
    LaunchedEffect(initialPeriod) { vm.period.value = initialPeriod }

    val state by vm.state.collectAsStateWithLifecycle()

    // Start / End are chosen INDEPENDENTLY, each in its own single-date dialog. Once both are set, the
    // range is pushed to the VM and the window becomes CUSTOM.
    var startMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var endMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf<DateField?>(null) }

    InsightsContent(
        state = state,
        customStartMs = startMs,
        customEndMs = endMs,
        onSelectPeriod = { vm.period.value = it },
        onPickStart = { picking = DateField.START },
        onPickEnd = { picking = DateField.END },
        onOpenLocationSettings = onOpenLocationSettings,
        onOpenTransaction = onOpenTransaction,
        onOpenCategory = { label -> onOpenCategory(label, state.period, vm.customRange.value) },
        onOpenReview = onOpenReview,
        view = view,
        onViewChange = onViewChange,
    )

    picking?.let { field ->
        val isStart = field == DateField.START
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = if (isStart) startMs else endMs,
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                val sel = dpState.selectedDateMillis
                TextButton(
                    enabled = sel != null,
                    onClick = {
                        if (sel != null) {
                            if (isStart) startMs = sel else endMs = sel
                            val s = startMs
                            val e = endMs
                            if (s != null && e != null) {
                                vm.customRange.value = s to e
                                vm.period.value = InsightsPeriod.CUSTOM
                            }
                        }
                        picking = null
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) {
            DatePicker(
                state = dpState,
                // Custom titled header with real top spacing (the default range header sat cramped).
                title = {
                    Text(
                        if (isStart) "Select start date" else "Select end date",
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                },
            )
        }
    }
}

private enum class DateField { START, END }

/** The chart vs map view — a saved UI mode, NOT a nav Route (the map shares the chart's window). Hoisted
 *  to [com.goushik.upiwallet.ui.nav.AppShell] so it survives the full-screen detail swap: opening a payment
 *  from the map place-sheet and pressing Back returns to Map, not a re-defaulted Chart. */
enum class InsightsView { CHART, CATEGORIES, MAP }

/** Stateless layout — pure over [InsightsUiState] so the @Preview never touches the lateinit repo. */
@Composable
fun InsightsContent(
    state: InsightsUiState,
    customStartMs: Long?,
    customEndMs: Long?,
    onSelectPeriod: (InsightsPeriod) -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onOpenLocationSettings: () -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
    onOpenCategory: (String) -> Unit = {},
    onOpenReview: () -> Unit = {},
    view: InsightsView = InsightsView.CHART,
    onViewChange: (InsightsView) -> Unit = {},
) {
    // Reset the dragged marker whenever the window changes (period switch or new data) — keyed on the
    // period too, so switching between two same-length windows still clears a stale selection.
    var selected by remember(state.period, state.data.points.size) { mutableStateOf(-1) }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = 20.dp, end = 20.dp,
                top = topInset + 8.dp,
                bottom = BottomNavHeight + bottomInset + 16.dp,
            ),
    ) {
        Text("Insights", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(14.dp))
        ChartMapToggle(view, onSelect = onViewChange)
        Spacer(Modifier.height(16.dp))
        // The donut carries the total in its centre, so the shared headline is hidden in Categories.
        if (view != InsightsView.CATEGORIES) {
            TotalHeadline(state.data.totalPaise, state.data.rangeLabel, state.data.txnCount)
            Spacer(Modifier.height(16.dp))
        }
        PeriodFilter(active = state.period, onSelect = onSelectPeriod)
        if (state.period == InsightsPeriod.CUSTOM) {
            Spacer(Modifier.height(12.dp))
            CustomDateFields(customStartMs, customEndMs, onPickStart, onPickEnd)
        }
        Spacer(Modifier.height(16.dp))
        // The chart / categories / map take ALL remaining height — full-bleed on the aurora, no card.
        when (view) {
            InsightsView.MAP -> InsightsMap(
                map = state.map,
                onOpenLocationSettings = onOpenLocationSettings,
                onOpenTransaction = onOpenTransaction,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            InsightsView.CATEGORIES -> InsightsCategoriesView(
                slices = state.categories,
                totalPaise = state.data.totalPaise,
                rangeLabel = state.data.rangeLabel,
                txnCount = state.data.txnCount,
                onOpenCategory = onOpenCategory,
                onOpenReview = onOpenReview,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            InsightsView.CHART -> Box(Modifier.weight(1f).fillMaxWidth()) {
                InsightsChart(
                    points = state.data.points,
                    selectedIndex = selected,
                    onSelectIndex = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.data.txnCount == 0 && !state.loading) {
                    Text(
                        emptyCaption(state.period),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/** Full-width segmented glass pill (Chart · Categories · Map) — the active segment gets the violet fill. */
@Composable
private fun ChartMapToggle(active: InsightsView, onSelect: (InsightsView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(PillShape, blur = false)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToggleSegment("Chart", active == InsightsView.CHART, Modifier.weight(1f)) { onSelect(InsightsView.CHART) }
        ToggleSegment("Categories", active == InsightsView.CATEGORIES, Modifier.weight(1f)) { onSelect(InsightsView.CATEGORIES) }
        ToggleSegment("Map", active == InsightsView.MAP, Modifier.weight(1f)) { onSelect(InsightsView.MAP) }
    }
}

@Composable
private fun ToggleSegment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val base = if (selected) {
        Modifier
            .clip(PillShape)
            .background(Violet500.copy(alpha = 0.30f))
            .border(1.dp, Violet500, PillShape)
    } else {
        Modifier
    }
    Box(
        modifier.then(base).clip(PillShape).clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) White else TextSecondary,
        )
    }
}

// ──────────────────────────────── pieces ────────────────────────────────

/** The big window total, rendered like the Home hero balance, with a "Total spent · {range}" label and
 *  a faint payment count. */
@Composable
private fun TotalHeadline(totalPaise: Long, rangeLabel: String, txnCount: Int) {
    Column {
        val (rupees, frac) = Money.formatParts(totalPaise)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rupees, style = MaterialTheme.typography.displayLarge, color = TextPrimary)
            Text(
                frac,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp),
                color = White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Total spent" + if (rangeLabel.isNotEmpty()) " · $rangeLabel" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        if (txnCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                "$txnCount ${if (txnCount == 1) "payment" else "payments"}",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

private val PeriodLabels = listOf(
    InsightsPeriod.DAY to "Day",
    InsightsPeriod.WEEK to "Week",
    InsightsPeriod.MONTH to "Month",
    InsightsPeriod.YEAR to "Year",
    InsightsPeriod.CUSTOM to "Custom",
)

/** Five pill toggles. Horizontally scrollable so they never crowd. Active = violet fill + border + white
 *  text; inactive = glassSurface(blur=false) + secondary text. */
@Composable
private fun PeriodFilter(active: InsightsPeriod, onSelect: (InsightsPeriod) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PeriodLabels.forEach { (period, label) ->
            PeriodPill(label = label, selected = period == active, onClick = { onSelect(period) })
        }
    }
}

@Composable
private fun PeriodPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val base = if (selected) {
        Modifier
            .clip(PillShape)
            .background(Violet500.copy(alpha = 0.22f))
            .border(1.dp, Violet500, PillShape)
    } else {
        Modifier.glassSurface(PillShape, blur = false)
    }
    Box(
        base.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) White else TextSecondary,
        )
    }
}

/** Two independent date fields. Each opens its OWN single-date picker (not one combined range box). */
@Composable
private fun CustomDateFields(
    startMs: Long?,
    endMs: Long?,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField("Start date", startMs, onPickStart, Modifier.weight(1f))
        DateField("End date", endMs, onPickEnd, Modifier.weight(1f))
    }
}

@Composable
private fun DateField(label: String, ms: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .glassSurface(WalletShapes.medium, blur = false)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(3.dp))
        Text(
            ms?.let(::fmtDate) ?: "Pick a date",
            style = MaterialTheme.typography.bodyLarge,
            color = if (ms != null) TextPrimary else TextTertiary,
        )
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")
/** A date-picker value is 00:00 UTC of the picked day — read through [PickerDate], never the device zone. */
private fun fmtDate(pickerMs: Long): String = PickerDate.toLocalDate(pickerMs).format(DATE_FMT)

private fun emptyCaption(period: InsightsPeriod): String = when (period) {
    InsightsPeriod.DAY -> "No spend today yet"
    InsightsPeriod.WEEK -> "No spend this week yet"
    InsightsPeriod.MONTH -> "No spend this month yet"
    InsightsPeriod.YEAR -> "No spend this year yet"
    InsightsPeriod.CUSTOM -> "No spend in this range yet"
}

// ──────────────────────────────── preview ────────────────────────────────

private val SampleInsights = InsightsData(
    points = listOf(
        ChartPoint(0L, 18_500, "01"),
        ChartPoint(1L, 42_000, ""),
        ChartPoint(2L, 9_800, ""),
        ChartPoint(3L, 67_300, "08"),
        ChartPoint(4L, 31_200, ""),
        ChartPoint(5L, 88_400, ""),
        ChartPoint(6L, 12_000, "15"),
        ChartPoint(7L, 54_600, ""),
        ChartPoint(8L, 23_900, ""),
        ChartPoint(9L, 121_100, "22"),
        ChartPoint(10L, 40_500, ""),
        ChartPoint(11L, 15_300, "29"),
    ),
    totalPaise = 525_300,
    rangeLabel = "June 2026",
    txnCount = 47,
)

@Preview(heightDp = 900)
@Composable
private fun InsightsPreview() {
    UPIWalletTheme {
        WalletBackground {
            InsightsContent(
                state = InsightsUiState(loading = false, data = SampleInsights, period = InsightsPeriod.MONTH),
                customStartMs = null,
                customEndMs = null,
                onSelectPeriod = {},
                onPickStart = {},
                onPickEnd = {},
            )
        }
    }
}

@Preview(heightDp = 900)
@Composable
private fun InsightsCustomPreview() {
    UPIWalletTheme {
        WalletBackground {
            InsightsContent(
                state = InsightsUiState(
                    loading = false,
                    data = InsightsData(emptyList(), 0L, "12 Jun – 18 Jun", 0),
                    period = InsightsPeriod.CUSTOM,
                ),
                customStartMs = 1_749_686_400_000L,
                customEndMs = null,
                onSelectPeriod = {},
                onPickStart = {},
                onPickEnd = {},
            )
        }
    }
}
