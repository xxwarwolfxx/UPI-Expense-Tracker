package com.goushik.upiwallet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.IconInfo
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.RemovableChip
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import kotlinx.coroutines.launch

private sealed interface ProfileState {
    data object Loading : ProfileState
    // profile may be null (no row yet) — Save then seeds onboardedAt rather than nulling it.
    data class Loaded(val profile: UserProfileEntity?) : ProfileState
}

/**
 * Edit display name + own UPI VPAs after onboarding (USER-FLOW: "you can add more later").
 * Full-screen over the shared aurora canvas. Editable state is seeded inside [EditProfileBody], so it
 * mirrors the profile loaded by produceState — not the not-yet-loaded initial value.
 */
@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val state by produceState<ProfileState>(ProfileState.Loading) {
        value = ProfileState.Loaded(ServiceLocator.repository.profile())
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        when (val s = state) {
            ProfileState.Loading -> CenterNote("Loading…")
            is ProfileState.Loaded -> EditProfileBody(s.profile, onBack)
        }
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
        Text("You", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileBody(profile: UserProfileEntity?, onBack: () -> Unit) {
    // remember runs on first entry of this body — i.e. after the profile has loaded.
    var name by remember { mutableStateOf(profile?.displayName ?: "") }
    var vpas by remember {
        mutableStateOf(
            profile?.ownVpasCsv.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
    var newVpa by remember { mutableStateOf("") }

    Spacer(Modifier.height(12.dp))
    FieldLabel("Your name", "as it shows on UPI")
    WalletTextField(name, { name = it }, placeholder = "e.g. Bram Stoker", textStyle = MaterialTheme.typography.titleLarge)

    Spacer(Modifier.height(18.dp))
    FieldLabel("Your UPI IDs")
    if (vpas.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vpas.forEach { v -> RemovableChip(v) { vpas = vpas - v } }
        }
        Spacer(Modifier.height(10.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            WalletTextField(
                newVpa, { newVpa = it }, placeholder = "name@bank",
                textStyle = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(10.dp))
        AddButton(enabled = newVpa.isNotBlank()) {
            val v = newVpa.trim()
            if (v.isNotEmpty() && v !in vpas) vpas = vpas + v
            newVpa = ""
        }
    }
    Spacer(Modifier.height(14.dp))
    HintRow("Find these in GPay → your profile. You can add more later.")

    Spacer(Modifier.height(28.dp))
    PrimaryButton(
        "Save",
        enabled = name.isNotBlank(),
        onClick = {
            // Process-scoped so the write survives this composable being swapped out on back-nav.
            ServiceLocator.appScope.launch {
                ServiceLocator.repository.upsertProfile(
                    UserProfileEntity(
                        displayName = name.trim(),
                        ownVpasCsv = vpas.joinToString(","),
                        // CRITICAL: carry the existing onboardedAt; nulling it re-triggers onboarding.
                        onboardedAt = profile?.onboardedAt ?: System.currentTimeMillis(),
                        // …and the wallet mode, or editing the profile would silently reset it to balance.
                        showBalance = profile?.showBalance ?: true,
                    ),
                )
            }
            onBack()
        },
    )
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit) {
    Text(
        "Add",
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) TextPrimary else TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Violet500.copy(alpha = 0.22f) else FieldBg)
            .border(1.dp, if (enabled) Violet500.copy(alpha = 0.5f) else HairlineColor, RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 15.dp),
    )
}

@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconInfo(TextTertiary, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
    }
}
