package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One spending cap, keyed by its period ("DAY" | "WEEK" | "MONTH" — an [com.goushik.upiwallet.domain.insights.InsightsPeriod]
 * name). A row exists ONLY when the user has set a limit for that period; clearing a limit deletes the row,
 * so "no budget" is simply the absence of a row.
 *
 * The two `lastAlerted*` fields make the 80% / 100% nudge fire at most ONCE per threshold per period:
 * [lastAlertedPeriodStart] is the epoch-ms window start we last alerted within, and [lastAlertedThreshold]
 * the highest threshold (0 / 80 / 100) already nudged in it. A new period (a different window start)
 * re-arms both — so you get at most one 80% buzz and one over-budget buzz each day/week/month.
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val period: String,
    val limitPaise: Long,
    val updatedAt: Long,
    val lastAlertedThreshold: Int = 0,
    val lastAlertedPeriodStart: Long = 0L,
)
