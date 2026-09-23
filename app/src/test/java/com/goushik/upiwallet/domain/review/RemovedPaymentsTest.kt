package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PURE host tests for [RemovedPayments] — what a removed payment comes back as. Synthetic rows only. */
class RemovedPaymentsTest {

    @Test fun `bank-confirmed means an RRN AND an SMS in the source`() {
        assertTrue(RemovedPayments.isBankConfirmed(row(Source.A11Y_SMS, rrn = "111122223333")))
        assertTrue(RemovedPayments.isBankConfirmed(row(Source.SMS, rrn = "111122223333")))
        assertFalse(RemovedPayments.isBankConfirmed(row(Source.A11Y_SMS, rrn = null)))
        assertFalse(RemovedPayments.isBankConfirmed(row(Source.A11Y, rrn = null)))
        assertFalse(RemovedPayments.isBankConfirmed(row(Source.MANUAL, rrn = null)))
    }

    @Test fun `put back - an RRN always comes back CONFIRMED`() {
        assertEquals(TxnStatus.CONFIRMED, RemovedPayments.putBackStatus(row(Source.A11Y_SMS, rrn = "111122223333")))
        assertEquals(TxnStatus.CONFIRMED, RemovedPayments.putBackStatus(row(Source.SMS, rrn = "111122223333")))
    }

    @Test fun `put back - a hand-entered payment stays CONFIRMED, not downgraded`() {
        assertEquals(TxnStatus.CONFIRMED, RemovedPayments.putBackStatus(row(Source.MANUAL, rrn = null)))
    }

    @Test fun `put back - a screen-only capture comes back UNCONFIRMED`() {
        assertEquals(TxnStatus.UNCONFIRMED, RemovedPayments.putBackStatus(row(Source.A11Y, rrn = null)))
        assertEquals(TxnStatus.UNCONFIRMED, RemovedPayments.putBackStatus(row(Source.SMS, rrn = null)))
    }

    @Test fun `undo restores exactly the previous status`() {
        val r = row(Source.A11Y, rrn = null)
        for (s in listOf(TxnStatus.PENDING, TxnStatus.UNCONFIRMED, TxnStatus.CONFIRMED)) {
            assertEquals(s, RemovedPayments.undoStatus(r, s))
        }
    }

    @Test fun `undo - bank proof that arrived meanwhile wins`() {
        val proven = row(Source.A11Y_SMS, rrn = "111122223333")
        assertEquals(TxnStatus.CONFIRMED, RemovedPayments.undoStatus(proven, TxnStatus.UNCONFIRMED))
    }

    @Test fun `undo never restores to DISCARDED, and copes with a vanished row`() {
        assertEquals(TxnStatus.UNCONFIRMED, RemovedPayments.undoStatus(row(Source.A11Y, null), TxnStatus.DISCARDED))
        assertEquals(TxnStatus.PENDING, RemovedPayments.undoStatus(null, TxnStatus.PENDING))
    }

    private fun row(source: String, rrn: String?) = TransactionEntity(
        id = "x", amountPaise = 51_000, direction = Direction.DEBIT, status = TxnStatus.DISCARDED,
        payeeName = "ACME Stores", rrn = rrn, timestampEvent = 1_000L, timestampCaptured = 1_000L, source = source,
    )
}
