package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.ConfirmSheetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PURE host tests for the display formatters in TxnFormat.kt. */
class TxnFormatTest {

    // ── which app a payment came from ──

    @Test fun `each captured app is named for what it is`() {
        assertEquals("Google Pay", upiAppName("com.google.android.apps.nbu.paisa.user"))
        assertEquals("PhonePe", upiAppName("com.phonepe.app"))
        assertEquals("Paytm", upiAppName("net.one97.paytm"))
        assertEquals("CRED", upiAppName("com.dreamplug.androidapp"))
    }

    @Test fun `an unknown or missing app is neutral - never Google Pay`() {
        assertEquals(UNKNOWN_UPI_APP, upiAppName(null))
        assertEquals(UNKNOWN_UPI_APP, upiAppName("com.example.somewallet"))
        assertEquals("Payment app", UNKNOWN_UPI_APP)
    }

    @Test fun `the names cover exactly the apps the capture reads`() {
        assertEquals(ConfirmSheetRegistry.default().packages, UPI_APP_NAMES.keys)
    }

    @Test fun `source label names the real app for screen captures`() {
        assertEquals("PhonePe", sourceLabel(Source.A11Y, "com.phonepe.app"))
        assertEquals("CRED + bank SMS", sourceLabel(Source.A11Y_SMS, "com.dreamplug.androidapp"))
        assertEquals("Payment app", sourceLabel(Source.A11Y))
        assertEquals("Payment app + bank SMS", sourceLabel(Source.A11Y_SMS, null))
    }

    @Test fun `SMS and manual labels are unchanged, whatever package is passed`() {
        assertEquals("Bank SMS", sourceLabel(Source.SMS))
        assertEquals("Bank SMS", sourceLabel(Source.SMS, "VM-HDFCBK"))
        assertEquals("Added manually", sourceLabel(Source.MANUAL))
    }

    // ── your UPI IDs ──

    @Test fun `a typed but un-added ID is kept, trimmed`() {
        assertEquals(listOf("ramesh@okaxis"), withPendingUpiId(emptyList(), "  ramesh@okaxis "))
        assertEquals(listOf("a@x", "ramesh@okaxis"), withPendingUpiId(listOf("a@x"), "ramesh@okaxis"))
    }

    @Test fun `a blank box or a repeat adds nothing`() {
        assertEquals(listOf("a@x"), withPendingUpiId(listOf("a@x"), "   "))
        assertEquals(listOf("a@x"), withPendingUpiId(listOf("a@x"), ""))
        assertEquals(listOf("ramesh@okaxis"), withPendingUpiId(listOf("ramesh@okaxis"), "Ramesh@OKAXIS"))
    }

    @Test fun `the summary shows the IDs themselves`() {
        assertEquals("ramesh@okaxis", upiIdsSummary(listOf("ramesh@okaxis")))
        assertEquals("ramesh@okaxis · ramesh@oksbi", upiIdsSummary(listOf("ramesh@okaxis", "ramesh@oksbi")))
        assertEquals("a@x · b@y · +2 more", upiIdsSummary(listOf("a@x", "b@y", "c@z", "d@w")))
    }

    @Test fun `the summary cuts a very long ID and is null when there are none`() {
        val long = "averyveryverylongname.with.dots@okhdfcbank"
        val out = upiIdsSummary(listOf(long))!!
        assertEquals(28, out.length)
        assertEquals('…', out.last())
        assertNull(upiIdsSummary(emptyList()))
        assertNull(upiIdsSummary(listOf(" ", "")))
    }
}
