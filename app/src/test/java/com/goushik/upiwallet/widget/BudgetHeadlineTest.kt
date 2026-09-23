package com.goushik.upiwallet.widget

import com.goushik.upiwallet.domain.budget.budgetMeter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The budget widget says what Home, Budgets and the nudge say: "% left" under the cap, "limit reached" exactly
 * at it, and the percent USED once past it — never "0%" above "over budget".
 */
class BudgetHeadlineTest {

    private val cap = 1_200_000L // ₹12,000

    private fun headline(spent: Long) = budgetHeadline(spent, cap, budgetMeter(spent, cap, 6).pctLeft)

    @Test fun `under the cap it shows the percent left, unchanged`() {
        assertEquals(BudgetHeadline(29, BudgetLabel.LEFT), headline(854_000))      // 71% used
        assertEquals(BudgetHeadline(100, BudgetLabel.LEFT), headline(0))
        assertEquals(BudgetHeadline(1, BudgetLabel.LEFT), headline(cap - 1))       // a sliver left
    }

    @Test fun `exactly at the cap it says limit reached`() {
        assertEquals(BudgetHeadline(0, BudgetLabel.AT_LIMIT), headline(cap))
    }

    @Test fun `past the cap it shows the percent used, never zero`() {
        assertEquals(BudgetHeadline(125, BudgetLabel.OVER), headline(1_500_000))
        assertEquals(BudgetHeadline(100, BudgetLabel.OVER), headline(cap + 1))
    }

    @Test fun `no cap falls back to the meter's own number`() {
        assertEquals(BudgetLabel.LEFT, budgetHeadline(10_000, 0L, 0).label)
    }

    @Test fun `the new labels read TEST in the test copy and stay plain in the release build`() {
        val main = strings("main/res/values/strings.xml")
        val debug = strings("debug/res/values/strings.xml")
        assertEquals("limit reached", main["widget_budget_at_limit"])
        assertEquals("of budget used", main["widget_budget_over_used"])
        for (key in listOf("widget_budget_at_limit", "widget_budget_over_used")) {
            assertEquals(key, "TEST · ${main[key]}", debug[key])
        }
    }

    private fun strings(path: String): Map<String, String> {
        val file = listOf(File("src/$path"), File("app/src/$path")).firstOrNull { it.exists() }
            ?: error("can't find src/$path from ${File(".").absolutePath}")
        return Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }
}
