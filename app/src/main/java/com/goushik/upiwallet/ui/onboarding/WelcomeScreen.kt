package com.goushik.upiwallet.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.R
import com.goushik.upiwallet.ui.common.GhostButton
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary

/** Onboarding screen 1 — first impression + the honest promise (mockup screen 1). */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onRestore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    WalletBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.weight(1f))

            // Brand mark: the app icon.
            Image(
                painter = painterResource(R.drawable.uet_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = Magenta500, ambientColor = Magenta500)
                    .clip(RoundedCornerShape(20.dp)),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "A wallet you\nglance at.",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "UPI Expense Tracker quietly captures every payment you make and shows two numbers: " +
                    "what you've spent and what's left. No receipts, no manual entry.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )

            Spacer(Modifier.weight(1f))

            PrimaryButton("Get started", onGetStarted, trailingArrow = true)
            Spacer(Modifier.height(10.dp))
            // Reinstalling? Restore an earlier backup file instead of starting fresh.
            GhostButton("Restore from a backup", onRestore)
            Spacer(Modifier.height(12.dp))
            Text(
                "Single-user · works offline · stays on your phone",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
