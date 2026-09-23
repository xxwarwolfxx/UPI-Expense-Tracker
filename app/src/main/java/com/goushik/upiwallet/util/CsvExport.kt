package com.goushik.upiwallet.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.insights.isSpend
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One-tap CSV export → Android share sheet (Drive / Gmail / Sheets). Plain util, NOT a Composable.
 * Spreadsheet-friendly: amount as a plain rupee number (no ₹, no thousands commas), RFC-4180 quoting,
 * CRLF line endings. Writes to cacheDir/exports/transactions.csv and shares it via FileProvider.
 *
 * The file holds exactly the payments the app shows ([exportable] — removed ones are left out, as they
 * are on every screen), and says per row whether the app counts it as spend and whether it's a transfer
 * between the user's own accounts. Without those two columns a spreadsheet sum of the debits came out at
 * more than double the app's spend, and the export looked like the app had lost track of money. The ISO
 * date column is one a spreadsheet can sort and group by month; the friendly date is kept beside it.
 */
object CsvExport {

    val HEADER = listOf(
        "Date", "Date (ISO)", "Direction", "Amount (rupees)", "Status",
        "Counted as spend", "Self-transfer",
        "Payee", "VPA", "Account", "RRN", "Category", "Source",
    )

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** The rows the app counts and shows — the same set as All transactions, so the counts agree. */
    fun exportable(txns: List<TransactionEntity>): List<TransactionEntity> =
        txns.filter { it.status != TxnStatus.DISCARDED }

    fun share(context: Context, txns: List<TransactionEntity>, ownVpas: Set<String>, ownNames: Set<String>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "transactions.csv")
        file.writeText(build(txns, ownVpas, ownNames))  // overwrite

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Export transactions")
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }

    /** The whole CSV document: header + one line per exportable row, CRLF-terminated. */
    fun build(
        txns: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { esc(it) }).append("\r\n")
        for (r in rows(txns, ownVpas, ownNames, zone)) {
            sb.append(r.joinToString(",") { esc(guard(it)) }).append("\r\n")
        }
        return sb.toString()
    }

    /** One column list per exportable txn, in [HEADER] order, unescaped. */
    fun rows(
        txns: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<List<String>> = exportable(txns).map { t ->
        listOf(
            DateTime.full(t.timestampEvent),
            Instant.ofEpochMilli(t.timestampEvent).atZone(zone).format(ISO),
            t.direction.name,
            // amountPaise is always non-negative — sign lives in `direction`. Plain number, no ₹/commas.
            // Locale.US keeps ASCII digits + a dot decimal regardless of device locale (spreadsheet-safe).
            String.format(Locale.US, "%d.%02d", t.amountPaise / 100, t.amountPaise % 100),
            t.status.name,
            yesNo(isSpend(t, ownVpas, ownNames)),
            yesNo(BalanceCalculator.isSelfTransfer(t, ownVpas, ownNames)),
            t.payeeName ?: t.payeeVpa ?: "",
            t.payeeVpa ?: "",
            (t.bankLabel ?: "") + (t.payerAccountLast4?.let { " ($it)" } ?: ""),
            t.rrn ?: "",
            t.category ?: "Uncategorized",
            t.source,
        )
    }

    private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

    /**
     * A payee name is text someone else chose. A cell starting with = + - @ (or a tab / CR) is read by a
     * spreadsheet as a formula, so it gets a leading apostrophe and shows as plain text.
     */
    fun guard(s: String): String =
        if (s.isNotEmpty() && s[0] in FORMULA_START) "'$s" else s

    private const val FORMULA_START = "=+-@\t\r"

    /** RFC-4180: quote a field containing comma/quote/CR/LF, doubling any internal quotes. */
    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
}
