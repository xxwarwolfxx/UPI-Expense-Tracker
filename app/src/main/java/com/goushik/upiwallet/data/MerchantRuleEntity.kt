package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-user learned merchant→category map (USER-FLOW: corrections that stick). A manual "fix category" in
 * the detail screen writes one of these (source = "user"); the categorizer consults them FIRST, so a fix
 * applies to future captures keyed by the same [matchKey] — the VPA localpart, or the lowercased payee
 * name for a11y-only rows that carry no VPA.
 */
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val matchKey: String,   // vpa-localpart, else lowercased payee name
    val category: String,               // Category.label
    val source: String,                 // "user" (manual fix) | "auto"
    val updatedAt: Long,
)
