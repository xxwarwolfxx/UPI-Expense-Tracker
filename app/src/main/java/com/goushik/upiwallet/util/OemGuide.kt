package com.goushik.upiwallet.util

import android.os.Build

/**
 * Per-manufacturer copy for the onboarding capture steps — the Settings breadcrumb that actually
 * matches the tester's phone, plus the one gotcha each skin is known for. Data-only: every brand
 * walks the SAME four steps; only the words change.
 *
 * Source of truth for the paths/gotchas is the researched OEM table in `web/install.html` (the
 * website's install guide) — if an entry changes there, mirror it here, and vice-versa.
 */
data class OemCopy(
    val key: String,
    /** Where the Accessibility list lives on this skin (breadcrumb under the step body). */
    val accessPath: List<String>,
    /** Warning shown on the unlock step (step 2). */
    val restrictedNote: String? = null,
    /** Warning shown on the accessibility steps (1 & 3). */
    val a11yNote: String? = null,
    /** Warning shown on the battery detail screen. */
    val batteryNote: String? = null,
)

object OemGuide {

    /** The accessibility service label users must find in Settings. */
    const val SERVICE_LABEL = "UPI Expense Tracker capture"

    fun current(): OemCopy = fromManufacturer(Build.MANUFACTURER)

    /** Pure + unit-testable mapping; lowercase-contains so "Redmi"/"POCO" land on the Xiaomi entry. */
    fun fromManufacturer(manufacturer: String?): OemCopy {
        val m = manufacturer?.trim()?.lowercase() ?: ""
        return when {
            "samsung" in m -> SAMSUNG
            "xiaomi" in m || "redmi" in m || "poco" in m -> XIAOMI
            "oppo" in m || "realme" in m -> OPPO
            "vivo" in m || "iqoo" in m -> VIVO
            "oneplus" in m -> ONEPLUS
            else -> DEFAULT
        }
    }

    private val DEFAULT = OemCopy(
        key = "default",
        accessPath = listOf("Settings", "Accessibility", "Downloaded apps", SERVICE_LABEL),
    )

    private val SAMSUNG = OemCopy(
        key = "samsung",
        accessPath = listOf("Settings", "Accessibility", "Installed apps", SERVICE_LABEL),
        restrictedNote = "On Samsung, if the switch stays greyed out, turn off Auto Blocker first: " +
            "Settings → Security and privacy → Auto Blocker. You can turn it back on after.",
    )

    private val XIAOMI = OemCopy(
        key = "xiaomi",
        accessPath = listOf("Settings", "Accessibility", SERVICE_LABEL),
        a11yNote = "Xiaomi can switch this off after a restart. If capture goes quiet, " +
            "turn “$SERVICE_LABEL” back on.",
    )

    private val OPPO = OemCopy(
        key = "oppo",
        accessPath = listOf("Settings", "Accessibility", SERVICE_LABEL),
        batteryNote = "ColorOS is strict about battery. Allow the app to run in the background " +
            "so it doesn't miss payments.",
    )

    private val VIVO = OemCopy(
        key = "vivo",
        accessPath = listOf("Settings", "Shortcuts & Accessibility", "Accessibility", SERVICE_LABEL),
    )

    private val ONEPLUS = OemCopy(
        key = "oneplus",
        accessPath = listOf("Settings", "System", "Accessibility", SERVICE_LABEL),
    )
}
