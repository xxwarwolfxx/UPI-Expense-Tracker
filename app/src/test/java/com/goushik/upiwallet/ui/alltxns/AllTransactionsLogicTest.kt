package com.goushik.upiwallet.ui.alltxns

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.ui.home.toRowUi
import com.goushik.upiwallet.util.DateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PURE host unit tests for the All-transactions screen's filter/group engine ([buildAllTxns]), the shared
 * row mapper ([toRowUi]), and the date-section helper ([DateTime.sectionLabel]). Every timestamp is built
 * in the system default zone and "now" is fixed, so the day-bucketing math is deterministic regardless of
 * the machine clock.
 */
class AllTransactionsLogicTest {

    private val ownVpas = setOf("bram@oksbi")
    private val ownNames = setOf("bram")
    private val zone: ZoneId = ZoneId.systemDefault()

    private val now = ZonedDateTime.of(2026, 6, 6, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    /** A timestamp `daysAgo` before "now", at the given local hour. */
    private fun atDay(daysAgo: Long, hour: Int = 12): Long =
        ZonedDateTime.of(2026, 6, 6, hour, 0, 0, 0, zone).minusDays(daysAgo).toInstant().toEpochMilli()

    private fun ids(s: AllTxnsUiState) = s.sections.flatMap { it.rows }.map { it.id }

    // ── grouping ────────────────────────────────────────────────────────────────

    @Test fun `sections group by day, newest first, rows newest-first within a day`() {
        val txns = listOf(
            txn("a", 100, atDay(0, 14), payeeName = "Swiggy"),
            txn("b", 200, atDay(0, 11), payeeName = "Blinkit"),
            txn("c", 300, atDay(1, 18), payeeName = "BESCOM"),
            txn("d", 400, atDay(40), payeeName = "Old"),
        )
        val s = buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(), now)
        assertEquals(4, s.totalCount)
        assertEquals(4, s.resultCount)
        assertEquals(listOf("Today", "Yesterday", DateTime.sectionLabel(atDay(40), now)), s.sections.map { it.header })
        assertEquals(listOf("a", "b"), s.sections[0].rows.map { it.id })   // 14:00 before 11:00
    }

    @Test fun `input order does not matter — output is sorted DESC`() {
        val txns = listOf(
            txn("old", 1, atDay(2)),
            txn("new", 1, atDay(0)),
            txn("mid", 1, atDay(1)),
        )
        assertEquals(listOf("new", "mid", "old"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(), now)))
    }

    // ── filters ─────────────────────────────────────────────────────────────────

    @Test fun `DISCARDED rows are excluded from totals and sections`() {
        val txns = listOf(
            txn("keep", 100, atDay(0)),
            txn("drop", 200, atDay(0), status = TxnStatus.DISCARDED),
        )
        val s = buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(), now)
        assertEquals(1, s.totalCount)
        assertEquals(listOf("keep"), ids(s))
    }

    @Test fun `direction SPENT keeps only debit spends — excludes credits and self-transfers`() {
        val txns = listOf(
            txn("spend", 100, atDay(0), direction = Direction.DEBIT, payeeName = "Shop"),
            txn("credit", 200, atDay(0), direction = Direction.CREDIT, payeeName = "Friend"),
            txn("self", 300, atDay(0), direction = Direction.DEBIT, payeeVpa = "bram@oksbi"),
        )
        assertEquals(listOf("spend"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(direction = DirFilter.SPENT), now)))
        assertEquals(listOf("credit"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(direction = DirFilter.RECEIVED), now)))
    }

    @Test fun `category filter matches the stored label`() {
        val txns = listOf(
            txn("f", 100, atDay(0), category = "Food"),
            txn("g", 200, atDay(0), category = "Groceries"),
        )
        assertEquals(listOf("f"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(categoryLabel = "Food"), now)))
    }

    @Test fun `account filter matches bankLabel and accounts list is distinct + sorted`() {
        val txns = listOf(
            txn("h1", 100, atDay(0), bankLabel = "HDFC"),
            txn("s1", 200, atDay(0), bankLabel = "SBI"),
            txn("h2", 300, atDay(1), bankLabel = "HDFC"),
        )
        val all = buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(), now)
        assertEquals(listOf("HDFC", "SBI"), all.accounts)
        assertEquals(listOf("s1"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(account = "SBI"), now)))
    }

    @Test fun `period THIS MONTH excludes a payment from last month`() {
        val txns = listOf(
            txn("now", 100, atDay(0)),    // 6 Jun — in June window
            txn("old", 200, atDay(40)),   // 27 Apr — outside June window
        )
        val s = buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(period = InsightsPeriod.MONTH), now)
        assertEquals(listOf("now"), ids(s))
    }

    @Test fun `search matches payee name case-insensitively`() {
        val txns = listOf(
            txn("a", 100, atDay(0), payeeName = "Swiggy"),
            txn("b", 200, atDay(0), payeeName = "Blinkit"),
        )
        assertEquals(listOf("a"), ids(buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(query = "SWIG"), now)))
    }

    @Test fun `filters compose with AND — category and account together`() {
        val txns = listOf(
            txn("x", 100, atDay(0), bankLabel = "HDFC", category = "Food"),
            txn("y", 200, atDay(0), bankLabel = "SBI", category = "Food"),
            txn("z", 300, atDay(0), bankLabel = "HDFC", category = "Groceries"),
        )
        val s = buildAllTxns(txns, ownVpas, ownNames, AllTxnsFilters(categoryLabel = "Food", account = "HDFC"), now)
        assertEquals(listOf("x"), ids(s))
        assertEquals(3, s.totalCount)   // totalCount is pre-filter
    }

    @Test fun `activeCount and anyActive track only narrowing filters`() {
        assertEquals(0, AllTxnsFilters().activeCount)
        assertFalse(AllTxnsFilters().anyActive)
        assertTrue(AllTxnsFilters(query = "x").anyActive)
        assertEquals(0, AllTxnsFilters(query = "x").activeCount)   // search isn't a "narrowing filter" count
        assertEquals(2, AllTxnsFilters(direction = DirFilter.SPENT, account = "HDFC").activeCount)
    }

    // ── mapper + section label ────────────────────────────────────────────────────

    @Test fun `toRowUi carries the core fields and flags self-transfers`() {
        val spend = txn("x", 38_500, atDay(0, 14), payeeName = "Swiggy", category = "Food").toRowUi(ownVpas, ownNames, now)
        assertEquals("x", spend.id)
        assertEquals(38_500L, spend.amountPaise)
        assertEquals(Direction.DEBIT, spend.direction)
        assertEquals("Food", spend.category)
        assertFalse(spend.isSelfTransfer)
        assertTrue(spend.title.isNotBlank())

        val self = txn("y", 10_000, atDay(0), payeeVpa = "bram@oksbi").toRowUi(ownVpas, ownNames, now)
        assertTrue(self.isSelfTransfer)
    }

    @Test fun `sectionLabel names Today and Yesterday`() {
        assertEquals("Today", DateTime.sectionLabel(atDay(0), now))
        assertEquals("Yesterday", DateTime.sectionLabel(atDay(1), now))
    }

    // ── fixture ─────────────────────────────────────────────────────────────────

    private fun txn(
        id: String, paise: Long, ts: Long,
        direction: Direction = Direction.DEBIT,
        status: TxnStatus = TxnStatus.CONFIRMED,
        payeeName: String? = null, payeeVpa: String? = null,
        bankLabel: String? = "HDFC", category: String? = null,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = direction, status = status,
        payeeName = payeeName, payeeVpa = payeeVpa, bankLabel = bankLabel, category = category,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )
}
