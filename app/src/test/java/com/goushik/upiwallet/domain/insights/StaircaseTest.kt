package com.goushik.upiwallet.domain.insights

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaircaseTest {

    private fun slice(label: String?, paise: Long) = CategorySlice(label, paise, 1)

    @Test
    fun `steps are ranked descending and heights follow spend`() {
        val steps = buildStairSteps(
            listOf(slice("Food", 30_000), slice("Shopping", 90_000), slice("Transport", 60_000)),
        )
        assertEquals(listOf("Shopping", "Transport", "Food"), steps.map { it.label })
        assertEquals(1f, steps[0].height01, 1e-6f)
        assertTrue(steps[0].height01 > steps[1].height01)
        assertTrue(steps[1].height01 > steps[2].height01)
    }

    @Test
    fun `arcs are contiguous, proportional to spend, and close the full circle`() {
        val steps = buildStairSteps(listOf(slice("A", 75_000), slice("B", 25_000)))
        // Contiguous: each step starts where the previous ended.
        assertEquals(steps[0].endRad, steps[1].startRad, 1e-5f)
        // Proportional: A = 3/4 of the circle.
        assertEquals(1.5f * PI.toFloat(), steps[0].endRad - steps[0].startRad, 1e-4f)
        // Closes: total sweep = 2π.
        assertEquals(2f * PI.toFloat(), steps.last().endRad - steps.first().startRad, 1e-4f)
    }

    @Test
    fun `tiny slices are floored so they stay visible`() {
        val steps = buildStairSteps(listOf(slice("Huge", 1_000_000), slice("Tiny", 1)))
        assertTrue(steps.last().height01 >= STAIR_MIN_HEIGHT)
    }

    @Test
    fun `zero-spend slices are dropped and empty input yields no steps`() {
        assertEquals(emptyList<StairStep>(), buildStairSteps(emptyList()))
        val steps = buildStairSteps(listOf(slice("A", 100), slice("B", 0)))
        assertEquals(listOf("A"), steps.map { it.label })
        // A single slice owns the whole ring at full height.
        assertEquals(2f * PI.toFloat(), steps[0].endRad - steps[0].startRad, 1e-4f)
        assertEquals(1f, steps[0].height01, 1e-6f)
    }

    @Test
    fun `uncategorized (null label) keeps its slot`() {
        val steps = buildStairSteps(listOf(slice(null, 50_000), slice("Food", 10_000)))
        assertEquals(null, steps[0].label)
        assertEquals(1f, steps[0].height01, 1e-6f)
    }
}
