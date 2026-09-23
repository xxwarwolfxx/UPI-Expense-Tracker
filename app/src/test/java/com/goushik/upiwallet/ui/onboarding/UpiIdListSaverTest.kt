package com.goushik.upiwallet.ui.onboarding

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Host tests for the saver that keeps setup's UPI IDs across a rotation or the app being killed. */
class UpiIdListSaverTest {

    private val anyValue = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun rotate(ids: List<String>): List<String>? {
        val saved = with(UpiIdListSaver) { anyValue.save(ids) } ?: return null
        return UpiIdListSaver.restore(saved)
    }

    @Test fun `the IDs come back in the order they were added`() {
        assertEquals(listOf("juniper@okaxis", "quill@okhdfcbank"), rotate(listOf("juniper@okaxis", "quill@okhdfcbank")))
        assertEquals(listOf("juniper@okaxis"), rotate(listOf("juniper@okaxis")))
    }

    @Test fun `no IDs yet comes back as an empty list, not as nothing`() {
        // Setup starts with no IDs, so this is the state a rotation on (or before) the UPI-ID step saves.
        assertEquals(emptyList<String>(), rotate(emptyList()))
    }

    @Test fun `why a plain listSaver is not used - it saves an empty list as nothing`() {
        // rememberSaveable restores a state saved as nothing as null, which a List<String> can't hold.
        val plain = listSaver<List<String>, String>(save = { it }, restore = { it })
        assertNull(with(plain) { anyValue.save(emptyList()) })
    }
}
