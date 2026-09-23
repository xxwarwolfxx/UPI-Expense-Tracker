package com.goushik.upiwallet.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The side-by-side debug build ("test copy") must never pass for the real app on the home screen: every
 * widget, and the capture-off reminder, reads "TEST · …" there. And the release build must look exactly as
 * before. Checked straight from the resource files, so a new widget layout with a hard-coded label, or a
 * debug override that drifts, fails here instead of on the owner's home screen.
 */
class TestCopyMarkingTest {

    /** Release text, unchanged from the literals these resources replaced. */
    private val releaseText = mapOf(
        "widget_brand" to "UPI ET",
        "widget_small_caption" to "Spent this month",
        "widget_budget_left" to "left this month",
        "widget_budget_over" to "over budget",
        "widget_budget_empty" to "Set a monthly budget",
        "widget_setup_title" to "Set up UPI Expense Tracker",
        "widget_paused_title" to "Capture paused",
        "capture_off_title" to "Payments aren't being recorded",
    )

    /** Debug labels that must be shorter than "TEST · <release text>" to fit the smallest widget cell. */
    private val shortened = setOf("widget_paused_title")

    @Test fun `release text is unchanged`() {
        val main = strings(res("main/res/values/strings.xml"))
        releaseText.forEach { (key, text) -> assertEquals(key, text, main[key]) }
    }

    @Test fun `the test copy marks every one of them TEST`() {
        val main = strings(res("main/res/values/strings.xml"))
        val debug = strings(res("debug/res/values/strings.xml"))
        releaseText.keys.forEach { key ->
            val d = debug[key]
            if (key in shortened) {
                // Too long with the prefix for the 100x90dp resize floor: a shorter TEST label is allowed.
                assertTrue("$key must start with 'TEST · ' in the test copy: $d", d?.startsWith("TEST · ") == true)
            } else {
                assertEquals(key, "TEST · ${main[key]}", d)
            }
        }
    }

    @Test fun `every widget layout carries at least one marked string`() {
        val layouts = res("main/res/layout").listFiles { f: File -> f.name.startsWith("widget_") && f.name.endsWith(".xml") }
            ?.toList().orEmpty()
        assertTrue("no widget layouts found", layouts.size >= 7)
        layouts.forEach { f ->
            val text = f.readText()
            assertTrue(
                "${f.name} has no TEST-marked string — a debug copy of it would look like the real widget",
                releaseText.keys.any { text.contains("@string/$it") },
            )
        }
    }

    private fun res(path: String): File =
        listOf(File("src/$path"), File("app/src/$path")).firstOrNull { it.exists() }
            ?: error("can't find src/$path from ${File(".").absolutePath}")

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent.replace("\\'", "'")
        }
    }
}
