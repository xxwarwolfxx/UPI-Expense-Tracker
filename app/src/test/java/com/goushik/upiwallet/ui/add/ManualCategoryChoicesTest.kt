package com.goushik.upiwallet.ui.add

import com.goushik.upiwallet.domain.categorize.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The Add-transaction category chips. "Transfer-to-self" is not offered: totals decide self-transfers
 * from the owner's own UPI IDs and name, never from the label, so picking it would still count the
 * payment as spending (and teach the label to that payee) — the same reason the detail screen hides it.
 */
class ManualCategoryChoicesTest {

    @Test fun `transfer-to-self is not offered when adding a payment`() {
        assertFalse(Category.SELF_TRANSFER in ManualCategoryChoices)
    }

    @Test fun `every other category is offered, in the usual order`() {
        assertEquals(Category.entries.filter { it != Category.SELF_TRANSFER }, ManualCategoryChoices)
    }
}
