package com.goushik.upiwallet.ui.settings

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.TogglePill
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.ui.theme.FieldBorder
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.WarnColor
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Permissions

/**
 * Opt-in for the Insights map's location pins (Slice C). OFF by default. Turning it on requests *precise*
 * location (FINE — COARSE's ~2 km is useless for neighborhood pins); the fix is read only at pay-time from
 * the cached last-known location, rounded to ~110 m, stored on-device, and never uploaded. Full-screen
 * over the shared aurora, same idiom as the other Settings sub-screens.
 */
@Composable
fun LocationSettingsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val store = ServiceLocator.locationSettings
    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(store.enabled) }
    var precise by remember { mutableStateOf(Permissions.isLocationGranted(ctx)) }
    var wantedOn by remember { mutableStateOf(store.enabled) } // user intent → drives the "needs Precise" hint

    // Android 12+ wants FINE requested alongside COARSE; "precise" = the FINE entry was granted (picking
    // "Approximate" grants only COARSE, which the accuracy gate rejects → no pins, by design).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        precise = granted
        enabled = granted // only stays on if precise location was actually granted
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)

        Spacer(Modifier.height(12.dp))
        Text("Spending map", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "Light up a map of where you were when you paid — each payment becomes a glowing pin in your " +
                "city, built only from your own movements and kept on this phone.",
            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
        )

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().glassSurface(WalletShapes.large).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show where I paid", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text("Off until you turn it on", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            }
            Spacer(Modifier.width(16.dp))
            TogglePill(enabled && precise) { wantOn ->
                wantedOn = wantOn
                when {
                    !wantOn -> enabled = false
                    precise -> enabled = true
                    else -> launcher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            }
        }

        if (wantedOn && !precise) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Precise location is needed — “Approximate” is only good to ~2 km, too coarse to place a pin. " +
                    "Grant Precise location to map your payments.",
                style = MaterialTheme.typography.bodySmall, color = WarnColor,
                modifier = Modifier.padding(start = 2.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Your location is read only at the moment you pay, from what your phone already knows — no extra " +
                "GPS, no battery hit. It's rounded to your neighbourhood (~110 m), stored on this phone, and " +
                "never uploaded. It shows where YOU were, not the shop. Works going forward — past payments " +
                "have no location.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary,
            modifier = Modifier.padding(start = 2.dp),
        )

        Spacer(Modifier.height(24.dp))
        PrimaryButton("Save", onClick = {
            store.enabled = enabled && precise
            onBack()
        })
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            IconChevronLeft(TextPrimary, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text("Spending map", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}
