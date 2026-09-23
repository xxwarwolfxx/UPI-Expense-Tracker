package com.goushik.upiwallet.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Switched on in Settings" must recognise our entry in both spellings Android uses for it. The Settings app
 * writes the long form; Android rewrites the whole list in the short form after any accessibility app is
 * force-stopped, updated or removed. Missing the short form made the release app read its switch as off.
 */
class A11yEntryMatchTest {

    private val cls = "com.goushik.upiwallet.capture.A11yCaptureService"
    private val release = "com.goushik.upiwallet"
    private val debug = "com.goushik.upiwallet.debug"

    @Test fun `the long form written by the Settings app matches`() {
        assertTrue(Permissions.a11yEntryMatches("$release/$cls", release, cls))
    }

    @Test fun `the short form Android writes after a force-stop matches for the release app`() {
        assertTrue(Permissions.a11yEntryMatches("$release/.capture.A11yCaptureService", release, cls))
    }

    @Test fun `the test copy's entry only has the long form, and it matches`() {
        // The debug package is not a prefix of the class name, so Android never shortens its entry.
        assertTrue(Permissions.a11yEntryMatches("$debug/$cls", debug, cls))
    }

    @Test fun `the other build's entry never matches`() {
        assertFalse(Permissions.a11yEntryMatches("$release/.capture.A11yCaptureService", debug, cls))
        assertFalse(Permissions.a11yEntryMatches("$release/$cls", debug, cls))
        assertFalse(Permissions.a11yEntryMatches("$debug/$cls", release, cls))
    }

    @Test fun `another app's service does not match`() {
        assertFalse(Permissions.a11yEntryMatches("com.example.reader/.ReaderService", release, cls))
        assertFalse(Permissions.a11yEntryMatches("com.example.reader/$cls", release, cls))
        assertFalse(Permissions.a11yEntryMatches("$release/.capture.OtherService", release, cls))
    }

    @Test fun `a malformed entry does not match`() {
        assertFalse(Permissions.a11yEntryMatches("", release, cls))
        assertFalse(Permissions.a11yEntryMatches(cls, release, cls))
        assertFalse(Permissions.a11yEntryMatches("/$cls", release, cls))
        assertFalse(Permissions.a11yEntryMatches("$release/", release, cls))
    }

    @Test fun `case is ignored as before, and stray spaces too`() {
        assertTrue(Permissions.a11yEntryMatches(" ${release.uppercase()}/.capture.A11yCaptureService ", release, cls))
    }
}
