package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PURE host tests for [DuplicateDetection.twin] — the Review focus queue's "did the same payment get
 * captured twice?" backstop for reconciler-missed twins. Same amount + direction + payee within ±10 min.
 */
class DuplicateDetectionTest {

    private val min = 60_000L

    @Test fun `a same-amount same-payee row one minute earlier is the twin`() {
        val target = txn("a", 1_185_000, ts = 100 * min, payeeName = "CRED Club")
        val earlier = txn("b", 1_185_000, ts = 99 * min, payeeName = "CRED Club")
        val all = listOf(target, earlier)
        assertEquals("b", DuplicateDetection.twin(target, all)?.id)
    }

    @Test fun `picks the earliest when more than one twin exists`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio")
        val t1 = txn("b", 50_000, ts = 98 * min, payeeName = "Zudio")
        val t2 = txn("c", 50_000, ts = 96 * min, payeeName = "Zudio")
        assertEquals("c", DuplicateDetection.twin(target, listOf(target, t1, t2))?.id)
    }

    @Test fun `outside the 10-minute window is not a twin`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio")
        val far = txn("b", 50_000, ts = 89 * min, payeeName = "Zudio") // 11 min apart
        assertNull(DuplicateDetection.twin(target, listOf(target, far)))
    }

    @Test fun `a different amount is not a twin`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio")
        val other = txn("b", 51_000, ts = 100 * min, payeeName = "Zudio")
        assertNull(DuplicateDetection.twin(target, listOf(target, other)))
    }

    @Test fun `a different direction is not a twin`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio")
        val credit = txn("b", 50_000, ts = 100 * min, direction = Direction.CREDIT, payeeName = "Zudio")
        assertNull(DuplicateDetection.twin(target, listOf(target, credit)))
    }

    @Test fun `same amount but a different known payee is not a twin`() {
        val target = txn("a", 4_321, ts = 100 * min, payeeName = "Juniper Quill T")
        val other = txn("b", 4_321, ts = 100 * min, payeeName = "K Brambleton")
        assertNull(DuplicateDetection.twin(target, listOf(target, other)))
    }

    @Test fun `matches on amount and time when a payee key is missing on either side`() {
        // No name/vpa to compare → amount + window + direction is the signal (lenient).
        val target = txn("a", 4_200, ts = 100 * min, payeeName = "Navi Finserv")
        val noKey = txn("b", 4_200, ts = 99 * min) // payeeName + payeeVpa both null
        assertEquals("b", DuplicateDetection.twin(target, listOf(target, noKey))?.id)
    }

    @Test fun `a DISCARDED candidate is never a twin`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio")
        val discarded = txn("b", 50_000, ts = 99 * min, payeeName = "Zudio", status = TxnStatus.DISCARDED)
        assertNull(DuplicateDetection.twin(target, listOf(target, discarded)))
    }

    @Test fun `a DISCARDED target has no twin`() {
        val target = txn("a", 50_000, ts = 100 * min, payeeName = "Zudio", status = TxnStatus.DISCARDED)
        val live = txn("b", 50_000, ts = 99 * min, payeeName = "Zudio")
        assertNull(DuplicateDetection.twin(target, listOf(target, live)))
    }

    @Test fun `vpa localpart matches even when the full handle differs`() {
        val target = txn("a", 99_900, ts = 100 * min, payeeVpa = "credclub@axis")
        val twin = txn("b", 99_900, ts = 99 * min, payeeVpa = "credclub@okhdfcbank")
        assertEquals("b", DuplicateDetection.twin(target, listOf(target, twin))?.id)
    }

    private fun txn(
        id: String,
        paise: Long,
        ts: Long,
        direction: Direction = Direction.DEBIT,
        status: TxnStatus = TxnStatus.CONFIRMED,
        payeeName: String? = null,
        payeeVpa: String? = null,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = direction, status = status,
        payeeName = payeeName, payeeVpa = payeeVpa,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )
}
