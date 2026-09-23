package com.goushik.upiwallet.ui.alltxns

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconChevronDown
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.home.TransactionListCard
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.theme.BorderColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.SurfaceColor
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface

/**
 * The full transaction history reached from Home's "View all →" — a full-screen child presented over Home
 * (the same swap idiom as Transaction detail / Add; AppShell hosts it as an [Overlay], hides the nav, and
 * routes its row taps to detail). Search + four filters (type / category / account / when) narrow a
 * date-grouped list. Pure filter/group logic lives in [buildAllTxns]; this file is just the chrome. Sits on
 * AppShell's aurora — no background of its own; owns its own [BackHandler].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTransactionsScreen(
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    // The opener (AppShell) resets this VM's filters when it OPENS the list ([AllTransactionsViewModel.startFrom]);
    // this screen never resets them itself, so coming back from a payment's detail keeps the search.
    vm: AllTransactionsViewModel = viewModel(factory = AllTransactionsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The search text is held HERE (not read back from the VM flow) so the field never lags a keystroke
    // behind the async state round-trip; each edit pushes into the VM for the actual filtering. It STARTS
    // from the VM's query, so the box always shows the search the list is actually filtered by.
    var query by rememberSaveable { mutableStateOf(vm.current.query) }
    // After process death the box's text is restored but the VM is new — push it back so they agree.
    LaunchedEffect(Unit) { if (vm.current.query != query) vm.setQuery(query) }
    var openSheet by remember { mutableStateOf<FilterKind?>(null) }
    var picking by remember { mutableStateOf<DateField?>(null) }
    // Date-picker values (00:00 UTC of the picked day), seeded from a drilled-in custom range.
    var customStart by rememberSaveable { mutableStateOf(vm.current.customStartMs) }
    var customEnd by rememberSaveable { mutableStateOf(vm.current.customEndMs) }

    AllTransactionsContent(
        state = state,
        query = query,
        onQueryChange = { query = it; vm.setQuery(it) },
        onBack = onBack,
        onOpenTransaction = onOpenTransaction,
        onOpenFilter = { openSheet = it },
        onClear = { query = ""; vm.clearFilters() },
    )

    when (openSheet) {
        FilterKind.DIRECTION -> FilterSheet(
            title = "Show",
            options = DirFilter.entries.map { FilterOption(it.name, it.label) },
            selectedKey = state.filters.direction.name,
            onSelect = { key -> vm.setDirection(DirFilter.valueOf(key!!)); openSheet = null },
            onDismiss = { openSheet = null },
        )
        FilterKind.CATEGORY -> FilterSheet(
            title = "Category",
            options = listOf(FilterOption(null, "All categories")) +
                Category.entries.map { FilterOption(it.label, it.label) },
            selectedKey = state.filters.categoryLabel,
            onSelect = { key -> vm.setCategory(key); openSheet = null },
            onDismiss = { openSheet = null },
        )
        FilterKind.ACCOUNT -> FilterSheet(
            title = "Account",
            options = listOf(FilterOption(null, "All accounts")) +
                state.accounts.map { FilterOption(it, it) },
            selectedKey = state.filters.account,
            onSelect = { key -> vm.setAccount(key); openSheet = null },
            onDismiss = { openSheet = null },
        )
        FilterKind.PERIOD -> FilterSheet(
            title = "When",
            options = listOf(
                FilterOption(null, "Anytime"),
                FilterOption(InsightsPeriod.DAY.name, "Today"),
                FilterOption(InsightsPeriod.WEEK.name, "This week"),
                FilterOption(InsightsPeriod.MONTH.name, "This month"),
                FilterOption(InsightsPeriod.YEAR.name, "This year"),
                FilterOption(InsightsPeriod.CUSTOM.name, "Custom range…"),
            ),
            selectedKey = state.filters.period?.name,
            onSelect = { key ->
                when (key) {
                    null -> { vm.setPeriod(null); openSheet = null }
                    InsightsPeriod.CUSTOM.name -> { openSheet = null; picking = DateField.START }
                    else -> { vm.setPeriod(InsightsPeriod.valueOf(key)); openSheet = null }
                }
            },
            onDismiss = { openSheet = null },
        )
        null -> {}
    }

    // Custom range: two independent single-date dialogs, chained start → end (mirrors InsightsScreen). The
    // CUSTOM window is applied only once BOTH dates are picked; cancelling either leaves the filter unchanged.
    picking?.let { field ->
        val isStart = field == DateField.START
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = if (isStart) customStart else customEnd,
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                val sel = dpState.selectedDateMillis
                TextButton(
                    enabled = sel != null,
                    onClick = {
                        if (sel == null) { picking = null; return@TextButton }
                        if (isStart) {
                            customStart = sel
                            picking = DateField.END
                        } else {
                            customEnd = sel
                            vm.setPeriod(InsightsPeriod.CUSTOM, customStart, customEnd)
                            picking = null
                        }
                    },
                ) { Text(if (isStart) "Next" else "Done") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) {
            DatePicker(
                state = dpState,
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

private enum class FilterKind { DIRECTION, CATEGORY, ACCOUNT, PERIOD }
private enum class DateField { START, END }
private data class FilterOption(val key: String?, val label: String)

@Composable
private fun AllTransactionsContent(
    state: AllTxnsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onOpenFilter: (FilterKind) -> Unit,
    onClear: () -> Unit,
) {
    BackHandler { onBack() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val f = state.filters

    Column(Modifier.fillMaxSize()) {
        // ── Header ──
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = topInset + 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { IconChevronLeft(TextPrimary, size = 20.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("All transactions", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(subtitle(state), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Search ──
        Box(Modifier.padding(horizontal = 20.dp)) {
            WalletTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search payments",
                textStyle = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── Filter pills (scroll so they never crowd) ──
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterPill(
                label = if (f.direction == DirFilter.ALL) "Type" else f.direction.label,
                active = f.direction != DirFilter.ALL,
                onClick = { onOpenFilter(FilterKind.DIRECTION) },
            )
            FilterPill(
                label = f.categoryLabel ?: "Category",
                active = f.categoryLabel != null,
                onClick = { onOpenFilter(FilterKind.CATEGORY) },
            )
            FilterPill(
                label = f.account ?: "Account",
                active = f.account != null,
                onClick = { onOpenFilter(FilterKind.ACCOUNT) },
            )
            FilterPill(
                label = f.period?.let { periodLabel(f) } ?: "When",
                active = f.period != null,
                onClick = { onOpenFilter(FilterKind.PERIOD) },
            )
            if (f.anyActive) ClearPill(onClear)
        }
        Spacer(Modifier.height(14.dp))

        // ── Date-grouped list ──
        if (state.sections.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (!state.loading) {
                    Text(
                        if (state.totalCount == 0) "No transactions yet" else "No payments match these filters",
                        style = MaterialTheme.typography.bodyLarge, color = TextTertiary,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomInset + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                state.sections.forEach { section ->
                    item(key = section.header) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                section.header,
                                style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                            )
                            TransactionListCard(section.rows, onOpenTransaction)
                        }
                    }
                }
            }
        }
    }
}

/** Filter pill: violet-filled + bold when narrowing, neutral glass + a chevron when at its default. */
@Composable
private fun FilterPill(label: String, active: Boolean, onClick: () -> Unit) {
    val base = if (active) {
        Modifier.clip(PillShape).background(Violet500.copy(alpha = 0.22f)).border(1.dp, Violet500, PillShape)
    } else {
        Modifier.glassSurface(PillShape, blur = false)
    }
    Row(
        base.clickable(onClick = onClick).padding(start = 16.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) White else TextSecondary,
        )
        Spacer(Modifier.width(5.dp))
        IconChevronDown(if (active) White else TextTertiary, size = 15.dp)
    }
}

@Composable
private fun ClearPill(onClick: () -> Unit) {
    Box(
        Modifier.clip(PillShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text("Clear ✕", style = MaterialTheme.typography.labelLarge, color = TextTertiary)
    }
}

/** A simple custom bottom sheet (scrim + opaque indigo panel) for single-select filters — kept custom so
 *  it matches the dark-glass language instead of Material's light defaults. Long lists scroll. */
@Composable
private fun FilterSheet(
    title: String,
    options: List<FilterOption>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(SheetShape).background(SurfaceColor).border(1.dp, BorderColor, SheetShape)
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp)
                    .size(width = 36.dp, height = 4.dp).clip(PillShape).background(BorderColor),
            )
            Text(
                title,
                style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEach { opt ->
                    val sel = opt.key == selectedKey
                    Row(
                        Modifier.fillMaxWidth().clip(WalletShapes.medium).clickable { onSelect(opt.key) }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (sel) White else TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (sel) IconCheck(Violet500, size = 18.dp)
                    }
                }
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/** Header subtitle: count, narrowed to "N of M" while any filter is active. Plain (non-composable). */
private fun subtitle(state: AllTxnsUiState): String = when {
    state.loading -> "Loading…"
    state.filters.anyActive -> "${state.resultCount} of ${state.totalCount} payments"
    else -> "${state.totalCount} ${if (state.totalCount == 1) "payment" else "payments"}"
}

private fun periodLabel(f: AllTxnsFilters): String = when (f.period) {
    InsightsPeriod.DAY -> "Today"
    InsightsPeriod.WEEK -> "This week"
    InsightsPeriod.MONTH -> "This month"
    InsightsPeriod.YEAR -> "This year"
    InsightsPeriod.CUSTOM -> customRangeLabel(f.customStartMs, f.customEndMs)
    null -> "When"
}

// ── Preview (sample data; the real screen binds to AllTransactionsViewModel) ──
private val SampleAllTxns = AllTxnsUiState(
    loading = false,
    totalCount = 4, resultCount = 4,
    accounts = listOf("HDFC", "SBI"),
    sections = listOf(
        TxnSection(
            "Today",
            listOf(
                TxnRowUi("1", "Swiggy", 38_500, Direction.DEBIT, false, false, "2:14 PM", "Food"),
                TxnRowUi("2", "Blinkit", 74_200, Direction.DEBIT, false, true, "11:02 AM", "Groceries"),
            ),
        ),
        TxnSection(
            "Yesterday",
            listOf(
                TxnRowUi("3", "BESCOM", 124_000, Direction.DEBIT, false, false, "6:30 PM", "Bills & Utilities"),
                TxnRowUi("4", "Rohan Mehta", 200_000, Direction.CREDIT, false, false, "9:10 AM"),
            ),
        ),
    ),
)

@Preview(heightDp = 900)
@Composable
private fun AllTransactionsPreview() {
    UPIWalletTheme {
        WalletBackground {
            AllTransactionsContent(
                state = SampleAllTxns,
                query = "",
                onQueryChange = {},
                onBack = {},
                onOpenTransaction = {},
                onOpenFilter = {},
                onClear = {},
            )
        }
    }
}
