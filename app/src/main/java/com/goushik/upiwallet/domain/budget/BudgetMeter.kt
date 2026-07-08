package com.goushik.upiwallet.domain.budget

import kotlin.math.max
import kotlin.math.roundToInt

/** Health band by % of the cap USED — calm (<80%), near (80–99%), over (≥100%). */
enum class BudgetBand { CALM, NEAR, OVER }

/**
 * PURE — the money-stack meter state for the budget widget. The stack has [BudgetMeter]-supplied [litTiers]
 * of `tiers` note-slots filled from the BOTTOM (money still left); the rest are ghosts (spent). [band]
 * follows % USED, deliberately decoupled from how full the stack looks (a nearly-empty calm stack is still
 * aurora, not amber). [pctLeft] is the headline number the widget shows. Host unit-tested (BudgetMeterTest).
 */
data class BudgetMeter(val pctLeft: Int, val litTiers: Int, val band: BudgetBand, val over: Boolean)

fun budgetMeter(spentPaise: Long, limitPaise: Long, tiers: Int): BudgetMeter {
    if (limitPaise <= 0L) return BudgetMeter(pctLeft = 0, litTiers = 0, band = BudgetBand.CALM, over = false)
    val used = spentPaise.toDouble() / limitPaise
    val fractionLeft = (1.0 - used).coerceIn(0.0, 1.0)
    val pctUsed = (used * 100).toInt()
    val band = when {
        pctUsed >= 100 -> BudgetBand.OVER
        pctUsed >= BudgetStatus.NEAR_PCT -> BudgetBand.NEAR
        else -> BudgetBand.CALM
    }
    val over = spentPaise >= limitPaise
    // Any positive headroom lights at least one tier; only at/over the cap does the stack go fully empty.
    val lit = if (fractionLeft <= 0.0) 0 else max(1, (fractionLeft * tiers).roundToInt())
    // % left as 100 − % used, so "used + left = 100" and it stays consistent with the card's % used.
    val pctLeft = (100 - pctUsed).coerceAtLeast(0)
    return BudgetMeter(pctLeft = pctLeft, litTiers = lit, band = band, over = over)
}
