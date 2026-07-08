package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row user profile captured during onboarding. Replaces the old hardcoded identity constants:
 * the entered display name + own UPI VPAs drive self-transfer netting, and
 * [onboardedAt] is the gate the app reads to decide onboarding-vs-home on launch.
 */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String = SINGLETON,
    val displayName: String,
    val ownVpasCsv: String,      // comma-separated UPI handles, stored as entered
    val onboardedAt: Long?,      // null until onboarding completes
    // Wallet mode (Phase C): true = show the available-balance wallet; false = spend-only (balance lives
    // behind a peek-eye on Home, and never on the widget). Reactive app-wide via this observed row.
    val showBalance: Boolean = true,
) {
    /** Own VPAs, normalized for matching. */
    fun ownVpaSet(): Set<String> =
        ownVpasCsv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Own name(s), normalized — the a11y debit leg of a self-transfer carries only the name. */
    fun ownNameSet(): Set<String> =
        setOfNotNull(displayName.trim().lowercase().ifEmpty { null })

    companion object {
        const val SINGLETON = "profile"
    }
}
