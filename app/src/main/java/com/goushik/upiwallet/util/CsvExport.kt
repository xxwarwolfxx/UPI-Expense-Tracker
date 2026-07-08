package com.goushik.upiwallet.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.goushik.upiwallet.data.TransactionEntity
import java.io.File
import java.util.Locale

/**
 * One-tap CSV export → Android share sheet (Drive / Gmail / Sheets). Plain util, NOT a Composable.
 * Spreadsheet-friendly: amount as a plain rupee number (no ₹, no thousands commas), RFC-4180 quoting,
 * CRLF line endings. Writes to cacheDir/exports/transactions.csv and shares it via FileProvider.
 */
object CsvExport {

    private val HEADER = listOf(
        "Date", "Direction", "Amount (rupees)", "Status",
        "Payee", "VPA", "Account", "RRN", "Category", "Source",
    )

    fun share(context: Context, txns: List<TransactionEntity>) {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { esc(it) }).append("\r\n")
        for (t in txns) {
            sb.append(row(t).joinToString(",") { esc(it) }).append("\r\n")
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "transactions.csv")
        file.writeText(sb.toString())  // overwrite

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

    /** One column list per txn, in HEADER order — escaped together at the call site. */
    private fun row(t: TransactionEntity): List<String> = listOf(
        DateTime.full(t.timestampEvent),
        t.direction.name,
        // amountPaise is always non-negative — sign lives in `direction`. Plain number, no ₹/commas.
        // Locale.US keeps ASCII digits + a dot decimal regardless of device locale (spreadsheet-safe).
        String.format(Locale.US, "%d.%02d", t.amountPaise / 100, t.amountPaise % 100),
        t.status.name,
        t.payeeName ?: t.payeeVpa ?: "",
        t.payeeVpa ?: "",
        (t.bankLabel ?: "") + (t.payerAccountLast4?.let { " ($it)" } ?: ""),
        t.rrn ?: "",
        t.category ?: "Uncategorized",
        t.source,
    )

    /** RFC-4180: quote a field containing comma/quote/CR/LF, doubling any internal quotes. */
    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
}
