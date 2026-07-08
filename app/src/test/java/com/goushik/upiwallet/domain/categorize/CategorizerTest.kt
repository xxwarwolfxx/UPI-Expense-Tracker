package com.goushik.upiwallet.domain.categorize

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The new rule: an uncategorised payment to an individual (P2P) is "Other" but NOT flagged for review,
 *  while an uncategorised business stays flagged. (Both unmatched by the merchant dictionary.) */
class CategorizerTest {

    @Test fun `unknown person payee is Other but NOT flagged for review`() {
        val r = Categorizer.categorize(debit("Ari Chandran S"), emptySet(), emptySet(), emptyMap())
        assertEquals(Category.OTHER, r.category)
        assertEquals("person", r.source)
        assertFalse(r.needsReview)
    }

    @Test fun `unknown business payee stays flagged for review`() {
        val r = Categorizer.categorize(debit("Navi Finserv Limited"), emptySet(), emptySet(), emptyMap())
        assertEquals(Category.OTHER, r.category)
        assertEquals("fallback", r.source)
        assertTrue(r.needsReview)
    }

    private fun debit(name: String) = TransactionEntity(
        id = "t1", amountPaise = 10_000, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
        payeeName = name, timestampEvent = 1_000, timestampCaptured = 1_000, source = Source.A11Y,
    )
}
