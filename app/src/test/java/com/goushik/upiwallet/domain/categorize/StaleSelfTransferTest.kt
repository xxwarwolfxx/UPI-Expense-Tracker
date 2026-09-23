package com.goushik.upiwallet.domain.categorize

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A "Transfer-to-self" label written under an older rule (or an older name / UPI IDs) must come off once the
 * row counts as spend again; otherwise Insights shows a "Transfer-to-self" slice inside spend. Only the pure
 * selection is tested here — the clearing is a guarded write in [Categorization.run].
 */
class StaleSelfTransferTest {

    private val ownVpas = setOf("bram@oksbi")
    private val ownNames = setOf("arun")
    private val self = Category.SELF_TRANSFER.label

    private fun row(
        id: String,
        name: String? = null,
        vpa: String? = null,
        category: String? = self,
        source: String? = "self",
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = id, amountPaise = 50_000, direction = Direction.DEBIT, status = status,
        payeeName = name, payeeVpa = vpa, timestampEvent = 1_000, timestampCaptured = 1_000,
        source = Source.A11Y, category = category, categoryConfidence = 1.0f, categorySource = source,
    )

    private fun stale(vararg rows: TransactionEntity) =
        Categorization.staleSelfTransferIds(rows.toList(), ownVpas, ownNames)

    @Test fun `a row the old substring rule called a transfer to yourself is picked up`() {
        // "tarun" contains "arun": labelled Transfer-to-self before, spend now.
        assertEquals(listOf("t1"), stale(row("t1", name = "Tarun")))
    }

    @Test fun `a row that is still a transfer to yourself keeps its label`() {
        assertEquals(emptyList<String>(), stale(row("t1", name = "Arun"), row("t2", vpa = "bram@oksbi")))
    }

    @Test fun `a sample row labelled by the seeder is picked up once its UPI ID is no longer yours`() {
        // The seeder writes the label with source "manual"; no screen lets a user pick it by hand.
        assertEquals(listOf("t1"), stale(row("t1", name = "Someone Else", vpa = "sample@okaxis", source = "manual")))
    }

    @Test fun `other categories and removed rows are left alone`() {
        assertEquals(
            emptyList<String>(),
            stale(
                row("t1", name = "Tarun", category = "Food", source = "manual"),
                row("t2", name = "Tarun", category = null, source = null),
                row("t3", name = "Tarun", status = TxnStatus.DISCARDED),
            ),
        )
    }

    @Test fun `with no name or UPI ID on the profile every label is stale`() {
        val rows = listOf(row("t1", name = "Arun"), row("t2", vpa = "bram@oksbi"))
        assertEquals(listOf("t1", "t2"), Categorization.staleSelfTransferIds(rows, emptySet(), emptySet()))
    }
}
