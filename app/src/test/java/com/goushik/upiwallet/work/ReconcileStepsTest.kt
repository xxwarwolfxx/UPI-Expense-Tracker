package com.goushik.upiwallet.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 15-minute run's order: the capture-off check must run even when the stale-payment sweep (a database
 * write) fails, and only the sweep's failure is worth a retry.
 */
class ReconcileStepsTest {

    private fun run(
        sweepFails: Boolean = false,
        tickFails: Boolean = false,
        budgetFails: Boolean = false,
    ): Pair<Boolean, List<String>> = runBlocking {
        val steps = mutableListOf<String>()
        val ok = runReconcileSteps(
            tick = { steps += "tick"; if (tickFails) error("tick") },
            sweep = { steps += "sweep"; if (sweepFails) error("disk full") },
            budget = { steps += "budget"; if (budgetFails) error("budget") },
            onFailure = { what, _ -> steps += "failed: $what" },
        )
        ok to steps
    }

    @Test fun `a normal run does all three and succeeds`() {
        val (ok, steps) = run()
        assertTrue(ok)
        assertEquals(listOf("tick", "sweep", "budget"), steps)
    }

    @Test fun `a failing sweep still lets the capture check and the budget check run, and asks for a retry`() {
        val (ok, steps) = run(sweepFails = true)
        assertFalse(ok)
        assertEquals(listOf("tick", "sweep", "failed: reconcile sweep", "budget"), steps)
    }

    @Test fun `the capture check runs before the sweep`() {
        val (_, steps) = run(sweepFails = true)
        assertTrue(steps.indexOf("tick") < steps.indexOf("sweep"))
    }

    @Test fun `a failing capture check or budget check does not ask for a retry`() {
        val (ok1, steps1) = run(tickFails = true)
        assertTrue(ok1)
        assertEquals(listOf("tick", "failed: capture check", "sweep", "budget"), steps1)
        val (ok2, _) = run(budgetFails = true)
        assertTrue(ok2)
    }
}
