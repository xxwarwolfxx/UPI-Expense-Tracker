package com.goushik.upiwallet.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.home.RecentSkeleton
import com.goushik.upiwallet.ui.home.TransactionListCard
import com.goushik.upiwallet.ui.home.TxnRowUi
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.UPIWalletTheme

/**
 * The Review tab — a **list** of the payments we weren't sure about (`needsReview`). Tapping a row opens
 * the full detail, where the category is picked (clearing the flag) and any duplicate twin is resolved;
 * the row then drops from this list reactively. The shared [TransactionListCard] keeps a payment looking
 * identical on Home, Review, and All-transactions. AppShell draws the aurora + hosts the nav, so this is
 * just a LazyColumn.
 */
@Composable
fun ReviewScreen(
    onOpenTransaction: (String) -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewContent(state = state, onOpenTransaction = onOpenTransaction)
}

@Composable
fun ReviewContent(
    state: ReviewUiState,
    onOpenTransaction: (String) -> Unit = {},
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ReviewHeader(state.items.size) }

        when {
            state.loading -> item { RecentSkeleton() }
            state.items.isEmpty() -> item { AllCaughtUp() }
            else -> item { TransactionListCard(state.items, onOpenTransaction) }
        }
    }
}

@Composable
private fun ReviewHeader(count: Int) {
    Column {
        Text("Review", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            if (count == 0) "Payments we weren't sure about land here."
            else "$count ${if (count == 1) "payment needs" else "payments need"} a quick look — " +
                "tap one to set a category.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        )
    }
}

@Composable
private fun AllCaughtUp() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(76.dp).clip(PillShape).background(GreenCredit.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            IconCheck(GreenCredit, size = 36.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("All caught up", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Nothing needs review right now.",
            style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
        )
    }
}

// ── Preview (sample data; the real screen binds to ReviewViewModel) ──
private val SampleRows = listOf(
    TxnRowUi("1", "CRED Club", 1_185_000, Direction.DEBIT, false, true, "2:40 PM", category = null, isPerson = false),
    TxnRowUi("2", "Navi Finserv", 420_000, Direction.DEBIT, false, true, "Yesterday", category = null, isPerson = false),
    TxnRowUi("3", "Ari Chandran S", 50_000, Direction.DEBIT, false, true, "Mon", category = null, isPerson = true),
)

@Preview(heightDp = 880)
@Composable
private fun ReviewListPreview() {
    UPIWalletTheme {
        WalletBackground {
            ReviewContent(ReviewUiState(loading = false, items = SampleRows))
        }
    }
}

@Preview(heightDp = 880)
@Composable
private fun ReviewEmptyPreview() {
    UPIWalletTheme {
        WalletBackground {
            ReviewContent(ReviewUiState(loading = false))
        }
    }
}
