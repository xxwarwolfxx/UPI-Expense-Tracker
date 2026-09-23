package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.Reconciler
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.ParserRegistry
import com.goushik.upiwallet.parse.RawCapture
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **The late-alert pass measured on the owner's real data.** Every HDFC/SBI debit text of the capture period
 * (the SMS corpus) is replayed in arrival order against the screen captures of the same period (the ledger
 * export), through a model of `Reconciler.onSms`'s search: the [SmsTiming] windows first, then the
 * [LateAlertMatch] window gated by `Reconciler.lateAlertFits`. The model leaves out the DISCARDED flip and
 * the removed rows; it is a measurement of the late pass, not a second reconciler.
 *
 * Ground truth: a screen row that v1.1.6 already merged with its HDFC alert carries that alert's RRN in the
 * export. A late merge onto such a row with a DIFFERENT RRN is a wrong pairing, and must never happen.
 *
 * Neither corpus is in git (real payees and amounts). They are found like the other corpus gates:
 * `UET_CORPUS` / `UET_SMS_CORPUS`, else by walking up to the main checkout's `backups/`. Without them the
 * test skips, unless `CI_REQUIRE_CORPUS` is set. Their sizes are asserted, and only counts are printed.
 */
class LateAlertCorpusTest {

    private class Text(val parsed: ParsedTxn, val arrivedAt: Long, val eventAt: Long)

    @Test
    fun `a late bank alert claims only its own payee's screen row on the owner's data`() {
        val exportFile = findExport()
        val smsFile = findSms()
        if (exportFile == null || smsFile == null) {
            if (System.getenv("CI_REQUIRE_CORPUS") != null) {
                fail("CI_REQUIRE_CORPUS is set but the ledger export or the SMS corpus is missing")
            }
            assumeTrue("no ledger export / SMS corpus on this machine — skipping (see the KDoc)", false)
        }

        // ── The screen captures, as they stood at pay time: no RRN yet. ──────────────────────────────────
        val export = JSONObject(exportFile!!.readText())
        val txArr = export.getJSONArray("transactions")
        assertTrue("export holds ${txArr.length()} rows — too few to be the real ledger", txArr.length() >= 100)
        val exportRrn = HashMap<String, String>()
        val screens = (0 until txArr.length()).map { txArr.getJSONObject(it) }
            .filter { it.getString("source") in setOf(Source.A11Y, Source.A11Y_SMS) }
            .filter { it.getString("direction") == Direction.DEBIT.name && it.getString("status") != TxnStatus.DISCARDED.name }
            .map { o ->
                if (!o.isNull("rrn")) exportRrn[o.getString("id")] = o.getString("rrn")
                TransactionEntity(
                    id = o.getString("id"), amountPaise = o.getLong("amountPaise"), direction = Direction.DEBIT,
                    status = TxnStatus.UNCONFIRMED, payeeName = o.optStr("payeeName"), payeeVpa = o.optStr("payeeVpa"),
                    bankLabel = o.optStr("bankLabel"), timestampEvent = o.getLong("timestampEvent"),
                    timestampCaptured = o.getLong("timestampCaptured"), source = Source.A11Y,
                )
            }
            .sortedBy { it.timestampEvent }
        val periodStart = screens.first().timestampEvent - 60 * 60_000L
        val periodEnd = export.getLong("exportedAt")

        // ── The bank debit texts of the same period, through the live parsers and the live SmsTiming. ────
        val msgs = JSONObject(smsFile!!.readText()).getJSONArray("messages")
        assertTrue("SMS corpus holds ${msgs.length()} texts — too few to be the real inbox", msgs.length() >= 13_000)
        val registry = ParserRegistry.default()
        val seenRrn = HashSet<String>()
        val texts = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            .filter { it.getLong("date") in periodStart..periodEnd }
            .mapNotNull { m ->
                val arrived = m.getLong("date")
                val parsed = registry.parse(RawCapture(Source.SMS, m.optString("body"), sender = m.optString("address"), capturedAt = arrived))
                    ?.takeIf { it.direction == Direction.DEBIT && it.rrn != null } ?: return@mapNotNull null
                Text(parsed, arrived, SmsTiming.eventTime(listOf(m.optLong("dateSent", 0L)), arrived))
            }
            .sortedBy { it.arrivedAt }
            .filter { seenRrn.add(it.parsed.rrn!!) } // a redelivered text is a DUPLICATE, not a second payment

        // ── Replay. ─────────────────────────────────────────────────────────────────────────────────────
        val claimed = HashSet<String>()
        var window = 0
        var standalone = 0
        var refusedPayee = 0
        var refusedBank = 0
        val late = mutableListOf<Pair<Text, TransactionEntity>>()
        val lookback = Reconciler.MATCH_WINDOW_MS + Reconciler.MARGIN_MS
        for (t in texts) {
            fun candidates(w: SmsTiming.Window) = screens.asSequence()
                .filter { it.id !in claimed && it.amountPaise == t.parsed.amountPaise }
                .filter { it.timestampEvent in w.from..w.to && it.timestampEvent <= t.arrivedAt }
                .sortedBy { abs(it.timestampEvent - w.pivot) }
                .toList()

            val inWindow = SmsTiming.matchWindows(t.eventAt, t.arrivedAt, lookback)
                .firstNotNullOfOrNull { candidates(it).firstOrNull() }
            if (inWindow != null) {
                claimed += inWindow.id
                window++
                continue
            }
            val lateCands = candidates(LateAlertMatch.window(t.eventAt))
            val fit = lateCands.firstOrNull { Reconciler.lateAlertFits(t.parsed, it) }
            for (c in lateCands.takeWhile { it !== fit }) {
                val payeeOk = LateAlertMatch.samePayee(t.parsed.payeeName, t.parsed.payeeVpa, c.payeeName, c.payeeVpa)
                if (payeeOk) refusedBank++ else refusedPayee++
            }
            if (fit != null) {
                claimed += fit.id
                late += t to fit
            } else {
                standalone++
            }
        }

        val wrong = late.count { (t, row) -> exportRrn[row.id]?.let { it != t.parsed.rrn } == true }
        val gapsMin = late.map { (t, row) -> (t.eventAt - row.timestampEvent) / 60_000 }
        println(
            "late-alert replay: ${texts.size} debit texts vs ${screens.size} screen rows -> window=$window " +
                "late=${late.size} (gaps min=$gapsMin) standalone=$standalone; refused: payee=$refusedPayee " +
                "bank=$refusedBank; wrong by ground truth=$wrong",
        )

        assertEquals("a late merge paired a text with a screen row another alert already proved", 0, wrong)
        // Checked with a spelling of its own, not the rule under test.
        assertTrue("a late merge crossed banks", late.none { (t, row) ->
            val screenBank = row.bankLabel?.lowercase() ?: return@none false
            val smsIsSbi = t.parsed.bankLabel == BankSenders.SBI
            val screenIsSbi = "sbi" in screenBank || "state bank" in screenBank
            val screenIsHdfc = "hdfc" in screenBank
            if (smsIsSbi) !screenIsSbi else !screenIsHdfc
        })

        if (txArr.length() == SNAPSHOT_TXNS && msgs.length() == SNAPSHOT_TEXTS) {
            // The one SBI alert the bank sent 57 min after its pay screen — booked twice before.
            assertEquals("late merges on the 2026-08-22 ledger", 1, late.size)
            assertEquals(BankSenders.SBI, late.single().first.parsed.bankLabel)
            assertTrue("gap ${gapsMin.single()} min", gapsMin.single() in 50L..60L)
            // One HDFC ₹1 alert whose only same-payee screen rows were paid from SBI (82 and 129 min back).
            assertEquals("same-payee rows refused for another bank", 2, refusedBank)
        }
    }

    private fun JSONObject.optStr(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun findExport(): File? {
        System.getenv("UET_CORPUS")?.let { return File(it).takeIf(File::isFile) }
        return walkUp { backups ->
            backups.listFiles { f: File -> f.isDirectory && f.name.startsWith("DEVICE-") }
                ?.sortedByDescending { it.name }
                ?.firstNotNullOfOrNull { File(it, "UET-backup-current.json").takeIf(File::isFile) }
        }
    }

    private fun findSms(): File? {
        System.getenv("UET_SMS_CORPUS")?.let { return File(it).takeIf(File::isFile) }
        return walkUp { backups ->
            File(backups, "sms-corpus")
                .listFiles { f: File -> f.isFile && f.name.startsWith("bank-sms-") && f.name.endsWith(".json") }
                ?.maxByOrNull { it.name }
        }
    }

    private fun walkUp(pick: (File) -> File?): File? {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            val backups = File(dir, "backups")
            if (backups.isDirectory) pick(backups)?.let { return it }
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        /** The ledger export (2026-08-22, 565 rows) and the inbox (13,158 texts) the pins were measured on. */
        const val SNAPSHOT_TXNS = 565
        const val SNAPSHOT_TEXTS = 13_158
    }
}
