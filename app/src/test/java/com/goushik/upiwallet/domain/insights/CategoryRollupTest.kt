package com.goushik.upiwallet.domain.insights

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host tests for [categoryRollup] — the Phase-D donut. The donut total lives only in the centre, so
 * the slices MUST sum to the chart's total over the SAME window/predicate (mirrors the map/chart
 * reconciliation in MapAndWindowTest).
 */
class CategoryRollupTest {

    private val ownVpas = setOf("bram@oksbi")
    private val ownNames = setOf("bram")
    private val now = 1_718_452_800_000L // ~2024-06-15, comfortably mid-month

    @Test fun `slices reconcile with the chart total and count over the same window`() {
        val txns = listOf(
            debit(1, 10_000, now, "Food"),
            debit(2, 25_000, now - 3_600_000, "Food"),
            debit(3, 50_000, now - 7_200_000, "Shopping"),
            debit(4, 5_000, now - 10_800_000, null), // uncategorized
            credit(5, 99_000, now), // not spend (credit)
            debit(6, 8_000, now, "Food", status = TxnStatus.DISCARDED), // excluded
        )
        val chart = bucketSpend(txns, ownVpas, ownNames, InsightsPeriod.MONTH, now)
        val slices = categoryRollup(txns, ownVpas, ownNames, InsightsPeriod.MONTH, now)

        assertEquals("slices must sum to the chart total", chart.totalPaise, slices.sumOf { it.spentPaise })
        assertEquals("slice counts must sum to the chart count", chart.txnCount, slices.sumOf { it.count })
        assertEquals(90_000L, slices.sumOf { it.spentPaise })
        assertEquals(4, slices.sumOf { it.count })
    }

    @Test fun `biggest category is first and null label is the uncategorized slice`() {
        val txns = listOf(
            debit(1, 10_000, now, "Food"),
            debit(2, 80_000, now, "Shopping"),
            debit(3, 5_000, now, null),
        )
        val slices = categoryRollup(txns, ownVpas, ownNames, InsightsPeriod.MONTH, now)
        assertEquals("Shopping", slices.first().label)
        assertTrue("an uncategorized (null-label) slice exists", slices.any { it.label == null })
        assertEquals(3, slices.size)
    }

    private fun debit(
        n: Int, paise: Long, ts: Long, category: String?,
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.DEBIT, status = status,
        payeeName = "Merchant $n", timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
        category = category,
    )

    private fun credit(n: Int, paise: Long, ts: Long) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
        timestampEvent = ts, timestampCaptured = ts, source = Source.SMS,
    )
}
