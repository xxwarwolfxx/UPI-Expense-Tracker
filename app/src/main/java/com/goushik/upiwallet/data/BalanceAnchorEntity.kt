package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Manual balance baseline per account. Available = Σ baselines − captured debits + captured credits
 * since the anchor. Phase 1 seeds this in code (no onboarding UI yet).
 */
@Entity(tableName = "balance_anchors")
data class BalanceAnchorEntity(
    @PrimaryKey val id: String,
    val accountLabel: String,              // "HDFC" | "SBI"
    val baselinePaise: Long,
    val anchoredAt: Long,
)
