package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PURE host tests for [CsvExport]: the spreadsheet must hold the same payments the app shows, say which
 * ones the app counts as spend and which are self-transfers, carry a sortable date, and keep a hostile
 * payee name from running as a formula. A debit sum over "Counted as spend = Yes" must equal the app's
 * spend — the "totals must reconcile" guard, carried into the export.
 */
class CsvExportTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val ownVpas = setOf("ramesh@okaxis")
    private val ownNames = setOf("ramesh kumar")

    private fun at(d: Int, h: Int, m: Int) =
        ZonedDateTime.of(2026, 9, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun txn(
        id: String, paise: Long, ts: Long,
        direction: Direction = Direction.DEBIT, status: TxnStatus = TxnStatus.CONFIRMED,
        name: String? = "ACME Stores", vpa: String? = null,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = direction, status = status,
        payeeName = name, payeeVpa = vpa, timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )

    private val ledger = listOf(
        txn("spend", 34_950, at(5, 14, 7)),
        txn("removed", 1_00_000_00, at(5, 15, 0), status = TxnStatus.DISCARDED),
        txn("self", 50_000_00, at(6, 9, 30), vpa = "ramesh@okaxis", name = "Ramesh Kumar"),
        txn("income", 20_000, at(7, 18, 45), direction = Direction.CREDIT, name = "Suresh"),
    )

    private fun col(name: String) = CsvExport.HEADER.indexOf(name)

    @Test fun `removed payments are left out, like on every screen`() {
        val rows = CsvExport.rows(ledger, ownVpas, ownNames, zone)
        assertEquals(3, rows.size)
        assertEquals(3, CsvExport.exportable(ledger).size)
        assertTrue(rows.none { it[col("Status")] == "DISCARDED" })
    }

    @Test fun `each row says whether it counts as spend and whether it is a self-transfer`() {
        val rows = CsvExport.rows(ledger, ownVpas, ownNames, zone)
        assertEquals(listOf("Yes", "No", "No"), rows.map { it[col("Counted as spend")] })
        assertEquals(listOf("No", "Yes", "No"), rows.map { it[col("Self-transfer")] })
    }

    @Test fun `summing the counted debits gives the app's spend`() {
        val rows = CsvExport.rows(ledger, ownVpas, ownNames, zone)
        val spend = rows.filter { it[col("Counted as spend")] == "Yes" }
            .sumOf { it[col("Amount (rupees)")].toBigDecimal() }
        assertEquals("349.50".toBigDecimal(), spend)
    }

    @Test fun `the ISO date column is sortable and in local time`() {
        val rows = CsvExport.rows(ledger, ownVpas, ownNames, zone)
        assertEquals(listOf("2026-09-05 14:07", "2026-09-06 09:30", "2026-09-07 18:45"), rows.map { it[col("Date (ISO)")] })
    }

    @Test fun `every row has one cell per header column`() {
        CsvExport.rows(ledger, ownVpas, ownNames, zone).forEach { assertEquals(CsvExport.HEADER.size, it.size) }
    }

    @Test fun `a payee name that starts like a formula is written as plain text`() {
        assertEquals("'=HYPERLINK(\"x\")", CsvExport.guard("=HYPERLINK(\"x\")"))
        assertEquals("'+91 98765", CsvExport.guard("+91 98765"))
        assertEquals("'-5", CsvExport.guard("-5"))
        assertEquals("'@sum", CsvExport.guard("@sum"))
        assertEquals("ACME Stores", CsvExport.guard("ACME Stores"))
        assertEquals("349.50", CsvExport.guard("349.50"))
        assertEquals("", CsvExport.guard(""))

        val csv = CsvExport.build(listOf(txn("f", 100, at(5, 10, 0), name = "=cmd|x")), ownVpas, ownNames, zone)
        assertTrue(csv.lines()[1].contains(",'=cmd|x,"))
    }

    @Test fun `the document is a header plus one CRLF line per row`() {
        val csv = CsvExport.build(ledger, ownVpas, ownNames, zone)
        val lines = csv.split("\r\n")
        assertEquals(CsvExport.HEADER.joinToString(","), lines[0])
        assertEquals(5, lines.size) // header + 3 rows + trailing empty after the last CRLF
        assertEquals("", lines.last())
    }
}
