package com.goushik.upiwallet.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.budget.BudgetStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.ui.common.CaptureDownBanner
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.common.rememberCaptureGrants
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.UPIWalletTheme
import com.goushik.upiwallet.util.Permissions

/** The real Home. Collects [HomeViewModel] state + live capture grants, delegates layout to the
 *  stateless [HomeContent] (keeps the screen dumb + previewable). */
@Composable
fun HomeScreen(
    onOpenTransaction: (String) -> Unit,
    onUpdateBalance: () -> Unit,
    onInsightsDay: () -> Unit,
    onInsightsWeek: () -> Unit,
    onInsightsMonth: () -> Unit,
    onViewAllRecent: () -> Unit,
    onOpenBudgets: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fancyCard by ServiceLocator.uiPrefs.fancyCard.collectAsStateWithLifecycle()
    val (grants, _) = rememberCaptureGrants()
    val ctx = LocalContext.current
    // One-time notification permission ask (Android 13+), so the "capture paused" reminder can reach him
    // even when the app is closed. The standard system dialog, once per install — no in-app UI.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ServiceLocator.uiPrefs.markNotificationsPrompted()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ServiceLocator.uiPrefs.notificationsPrompted()) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) ServiceLocator.uiPrefs.markNotificationsPrompted()
        else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    HomeContent(
        state = state,
        fancyCard = fancyCard,
        captureOff = grants.capturePaused,
        captureStuck = grants.captureStuck,
        onFixCapture = { Permissions.openAccessibilitySettings(ctx) },
        onOpenTransaction = onOpenTransaction,
        onUpdateBalance = onUpdateBalance,
        onInsightsDay = onInsightsDay,
        onInsightsWeek = onInsightsWeek,
        onInsightsMonth = onInsightsMonth,
        onViewAllRecent = onViewAllRecent,
        onOpenBudgets = onOpenBudgets,
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    fancyCard: Boolean,
    captureOff: Boolean,
    captureStuck: Boolean = false,
    onFixCapture: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onUpdateBalance: () -> Unit,
    onInsightsDay: () -> Unit,
    onInsightsWeek: () -> Unit,
    onInsightsMonth: () -> Unit,
    onViewAllRecent: () -> Unit,
    onOpenBudgets: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = topInset + 8.dp,
            bottom = BottomNavHeight + bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { TopBar(name = state.displayName) }

        if (captureOff) {
            item { CaptureDownBanner(onFix = onFixCapture, stuck = captureStuck) }
        }

        item {
            HeroBalanceCard(
                fancy = fancyCard,
                showBalance = state.showBalance,
                availablePaise = state.availableBalancePaise,
                accounts = state.accounts,
                accountMonthSpend = state.accountMonthSpend,
                monthSpentPaise = state.monthSpentPaise,
                monthCount = state.monthCount,
                monthBudget = state.monthBudget,
                onUpdateBalance = onUpdateBalance,
                onOpenBudgets = onOpenBudgets,
            )
        }

        item {
            if (state.showBalance) {
                StatPairFlat(
                    leftPaise = state.weekSpentPaise, leftCount = state.weekCount, onLeft = onInsightsWeek,
                    rightPaise = state.monthSpentPaise, rightCount = state.monthCount, onRight = onInsightsMonth,
                )
            } else {
                // Spend-only: the hero already owns "this month", so the tiles drop to today + this week.
                StatPairFlat(
                    leftPaise = state.todaySpentPaise, leftCount = state.todayCount, onLeft = onInsightsDay,
                    rightPaise = state.weekSpentPaise, rightCount = state.weekCount, onRight = onInsightsWeek,
                    leftTitle = "Today", rightTitle = "This week",
                )
            }
        }

        item { SectionHeader("Recent", "View all →", onAction = onViewAllRecent) }

        item {
            when {
                state.recentTxns.isNotEmpty() ->
                    TransactionListCard(state.recentTxns, onOpenTransaction)
                state.loading -> RecentSkeleton()
                else -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge, color = TextTertiary,
                    )
                }
            }
        }
    }
}

// ── Preview (sample data; the real screen binds to HomeViewModel) ──
private val SampleState = HomeUiState(
    loading = false,
    availableBalancePaise = 4_825_000,
    accounts = listOf(AccountBalance("HDFC", 4_000_000), AccountBalance("SBI", 1_000_000)),
    accountMonthSpend = listOf(AccountSpend("HDFC", 1_200_000), AccountSpend("SBI", 664_000)),
    monthSpentPaise = 1_864_000, monthCount = 47,
    weekSpentPaise = 421_500, weekCount = 11,
    todaySpentPaise = 82_000, todayCount = 2,
    recentTxns = listOf(
        TxnRowUi("1", "Swiggy", 38_500, Direction.DEBIT, false, false, "2:14 PM"),
        TxnRowUi("2", "Blinkit", 74_200, Direction.DEBIT, false, false, "11:02 AM"),
        TxnRowUi("3", "Namma Yatri", 6_800, Direction.DEBIT, false, true, "9:30 AM"),
        TxnRowUi("4", "Self transfer", 500_000, Direction.DEBIT, true, false, "Yesterday"),
        TxnRowUi("5", "Rohan Mehta", 200_000, Direction.CREDIT, false, false, "Yesterday"),
    ),
    displayName = "Bram Stoker",
)

// Spend-mode + a set monthly cap → exercises the on-card budget bar (engraved caption + V2 ridge). The
// budget's spent matches monthSpentPaise (₹18,640 of a ₹25,000 cap → 74%, calm), so it reconciles on-card.
private val SampleBudgetState = SampleState.copy(
    showBalance = false,
    monthBudget = BudgetStatus(
        period = InsightsPeriod.MONTH,
        limitPaise = 2_500_000,
        spentPaise = SampleState.monthSpentPaise,
        windowStart = 0L,
    ),
)

@Preview(heightDp = 900)
@Composable
private fun HomeBudgetPreview() {
    UPIWalletTheme {
        WalletBackground {
            HomeContent(state = SampleBudgetState, fancyCard = true, captureOff = false, onFixCapture = {}, onOpenTransaction = {}, onUpdateBalance = {}, onInsightsDay = {}, onInsightsWeek = {}, onInsightsMonth = {}, onViewAllRecent = {}, onOpenBudgets = {})
        }
    }
}

@Preview(heightDp = 900)
@Composable
private fun HomePreview() {
    UPIWalletTheme {
        WalletBackground {
            HomeContent(state = SampleState, fancyCard = true, captureOff = false, onFixCapture = {}, onOpenTransaction = {}, onUpdateBalance = {}, onInsightsDay = {}, onInsightsWeek = {}, onInsightsMonth = {}, onViewAllRecent = {}, onOpenBudgets = {})
        }
    }
}
