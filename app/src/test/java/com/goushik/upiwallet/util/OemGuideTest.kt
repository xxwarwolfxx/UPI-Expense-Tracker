package com.goushik.upiwallet.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PURE host unit tests for [OemGuide.fromManufacturer] — the Build.MANUFACTURER → per-skin copy
 * mapping the onboarding capture steps render. Sub-brands must land on their parent skin's entry
 * (Redmi/POCO → Xiaomi, Realme → Oppo, iQOO → Vivo); anything unknown falls back to stock/Pixel.
 */
class OemGuideTest {

    private fun keyOf(manufacturer: String?) = OemGuide.fromManufacturer(manufacturer).key

    @Test fun `samsung maps to samsung`() = assertEquals("samsung", keyOf("samsung"))

    @Test fun `xiaomi and its sub-brands map to xiaomi`() {
        assertEquals("xiaomi", keyOf("Xiaomi"))
        assertEquals("xiaomi", keyOf("Redmi"))
        assertEquals("xiaomi", keyOf("POCO"))
    }

    @Test fun `oppo and realme map to oppo`() {
        assertEquals("oppo", keyOf("OPPO"))
        assertEquals("oppo", keyOf("realme"))
    }

    @Test fun `vivo and iqoo map to vivo`() {
        assertEquals("vivo", keyOf("vivo"))
        assertEquals("vivo", keyOf("iQOO"))
    }

    @Test fun `oneplus maps to oneplus`() = assertEquals("oneplus", keyOf("OnePlus"))

    @Test fun `pixel and unknown brands fall back to default`() {
        assertEquals("default", keyOf("Google"))
        assertEquals("default", keyOf("motorola"))
        assertEquals("default", keyOf("Nothing"))
        assertEquals("default", keyOf(null))
        assertEquals("default", keyOf(""))
    }

    @Test fun `every entry names the accessibility service at the end of its path`() {
        for (m in listOf("samsung", "xiaomi", "oppo", "vivo", "oneplus", "google")) {
            assertEquals(OemGuide.SERVICE_LABEL, OemGuide.fromManufacturer(m).accessPath.last())
        }
    }
}
