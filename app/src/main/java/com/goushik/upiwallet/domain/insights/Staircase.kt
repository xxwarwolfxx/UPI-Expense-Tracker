package com.goushik.upiwallet.domain.insights

import kotlin.math.PI

/** One category step of the 3D spending staircase: its arc [startRad, endRad) and a 0..1 height. */
data class StairStep(
    val label: String?,
    val startRad: Float,
    val endRad: Float,
    val height01: Float,
)

/** Slivers stay visible: no step is shorter than this fraction of the tallest. */
const val STAIR_MIN_HEIGHT = 0.16f

/**
 * Lay the slices around the ring as the descending circular staircase: largest
 * spend first at [originRad] (screen-top), clockwise, each arc ∝ spend and each height ∝ spend — the
 * biggest category is the tallest/top step, descending around to the smallest, with a [STAIR_MIN_HEIGHT]
 * floor. Zero-spend slices are dropped. Pure → unit-tested.
 */
fun buildStairSteps(slices: List<CategorySlice>, originRad: Float = (-PI / 2).toFloat()): List<StairStep> {
    val ranked = slices.filter { it.spentPaise > 0 }.sortedByDescending { it.spentPaise }
    if (ranked.isEmpty()) return emptyList()
    val total = ranked.sumOf { it.spentPaise }.toFloat()
    val maxSpend = ranked.first().spentPaise.toFloat()
    var angle = originRad
    return ranked.map { s ->
        val sweep = (s.spentPaise / total) * 2f * PI.toFloat()
        val height = STAIR_MIN_HEIGHT + (1f - STAIR_MIN_HEIGHT) * (s.spentPaise / maxSpend)
        StairStep(s.label, angle, angle + sweep, height).also { angle += sweep }
    }
}
