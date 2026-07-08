package com.goushik.upiwallet.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletBackground
import com.goushik.upiwallet.ui.theme.AuroraBrush
import com.goushik.upiwallet.ui.theme.Magenta500
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White

/** Onboarding screen 1 — first impression + the honest promise (mockup screen 1). */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    WalletBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.weight(1f))

            // Brand mark — aurora (a brand moment, not a flat surface).
            Box(
                Modifier
                    .size(64.dp)
                    .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = Magenta500, ambientColor = Magenta500)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AuroraBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text("₹", style = MaterialTheme.typography.headlineLarge, color = White)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "A wallet you\nglance at.",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "UPI Expense Tracker quietly captures every payment you make and shows two numbers — " +
                    "what you've spent and what's left. No receipts, no manual entry.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(20.dp))
            HonestCard()

            Spacer(Modifier.weight(1f))

            PrimaryButton("Get started", onGetStarted, trailingArrow = true)
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

@Composable
private fun HonestCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x14F6A037))
            .border(1.dp, Color(0x47F6A037), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp, end = 12.dp)
                .size(width = 4.dp, height = 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(WarnColor),
        )
        Text(
            "Android may miss a payment now and then in the background. We catch those with " +
                "your bank's texts + a quick balance check — so the number stays honest.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE9D9BF),
        )
    }
}
