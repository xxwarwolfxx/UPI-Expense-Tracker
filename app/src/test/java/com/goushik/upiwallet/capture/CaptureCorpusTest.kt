package com.goushik.upiwallet.capture

import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.ConfirmSheetPatterns
import com.goushik.upiwallet.parse.ConfirmSheetRegistry
import com.goushik.upiwallet.parse.GpayConfirmSheetParser
import com.goushik.upiwallet.parse.QualifyVerdict
import com.goushik.upiwallet.parse.RawCapture
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **The merge gate for the capture rules.** Replays every screen the app ever recorded — the user's own
 * `raw_events.payload` rows, pulled off the Pixel — through the live parsers and checks both directions:
 * no real payment may be lost, and no known non-payment screen may survive.
 *
 * Why replay instead of hand-written fixtures: the app stores the exact text it read for every capture, so
 * the corpus IS the evidence. A timing heuristic found 13 suspect rows; replaying the stored payloads found
 * 36. Guesswork under-counts.
 *
 * HONEST LIMIT of this corpus: payloads are stored only when a screen CREATED a row, i.e. only screens
 * the rules of that day accepted. It therefore contains no terminal (success/failure) screens and no
 * screens the old rules rejected. What this gate proves is a regression bound — "nothing the old rules
 * accepted and the bank confirmed is lost, and every known phantom is now rejected" — not a claim about
 * screens never sampled. Terminal-decision behaviour is covered by TerminalDecisionTest, not here.
 *
 * **The corpus is deliberately NOT in git** — it contains real transactions, personal chats and payee
 * names, and this repo is ported file-by-file to the public F-Droid copy. It lives in the main checkout's
 * gitignored `backups/`, which this test finds by walking up (a worktree sits three levels below it), or
 * via the `UET_CORPUS` environment variable. When it is absent the test skips — which is why it also
 * asserts the corpus size: a silently-empty parse would otherwise look like a pass.
 *
 * Ground truth is independent of the rules being tested, so the gate can't mark its own homework:
 *  - **real** = a bank SMS matched the row (it carries an RRN, or merged to `a11y+sms`). The bank is the
 *    authority; nothing about the screen text decides this.
 *  - **foreign** = the payload contains another app's chrome (notification shade, browser, messenger). That
 *    label comes from app furniture, not from anything the parsers look at.
 */
class CaptureCorpusTest {

    private val registry = ConfirmSheetRegistry.default()

    /** Chrome that only ever belongs to another app — the class the window-ownership guard exists for. */
    private val foreignChrome = listOf(
        "Clear all notifications", "Notification settings", "Notification history",
        "Clear all silent notifications", "Customize and control Google Chrome", "Connection is secure",
        "Mark as read", "Show bubble",
    )

    @Test
    fun `every real payment still qualifies and every non-payment screen is rejected`() {
        val corpus = findCorpus()
        assumeTrue("no corpus on this machine — skipping (see the KDoc)", corpus != null)
        val root = JSONObject(corpus!!.readText())

        val txns = root.getJSONArray("transactions").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("id") }
        }
        val rawArr = root.getJSONArray("rawEvents")
        val screens = (0 until rawArr.length()).map { rawArr.getJSONObject(it) }
            .filter { it.optString("source") == "a11y" }
            .mapNotNull { ev ->
                val txn = txns[ev.optString("txnId")] ?: return@mapNotNull null
                Screen(
                    pkg = ev.optString("packageName"),
                    text = ev.optString("payload"),
                    amountPaise = txn.optLong("amountPaise"),
                    status = txn.optString("status"),
                    source = txn.optString("source"),
                    hasRrn = !txn.isNull("rrn"),
                    atMs = txn.optLong("timestampEvent"),
                    bankLabel = if (txn.isNull("bankLabel")) null else txn.optString("bankLabel"),
                )
            }

        // The corpus loaded and is the shape we expect — proves the gate actually ran.
        assertTrue("corpus parsed to ${screens.size} screens — that is too few to be the real export",
            screens.size >= 100)
        val live = screens.filter { it.status != "DISCARDED" }
        val proven = live.filter { it.hasRrn || it.source == "a11y+sms" }
        assertTrue("no bank-proven captures found — ground truth is missing", proven.size >= 50)

        // ── Direction 1: a payment the BANK confirmed must never be rejected. ──────────────────────
        // Carve-out: autopay mandate SETUPS are deliberately rejected even when bank-proven — the ₹ on
        // that screen is the mandate limit, and when money really moved at setup (all three sampled Google
        // Play cases) the bank's own debit SMS recorded it independently. The corpus itself is the proof:
        // every bank-proven setup here merged an SMS.
        val lost = proven.filter { !isMandateSetup(it) && verdict(it) == QualifyVerdict.REJECTED }
        assertEquals(
            "the rules dropped ${lost.size} bank-proven payments (${lost.joinToString { money(it.amountPaise) }}) " +
                "— delete the offending rule, do not tune it",
            0, lost.size,
        )
        // ...and the amount it reads back must be the amount that was booked.
        val misread = proven.filter { !isMandateSetup(it) }
            .filter { s -> qualify(s).let { it.verdict != QualifyVerdict.REJECTED && it.amountPaise != s.amountPaise } }
        assertEquals("${misread.size} bank-proven captures parse to the wrong amount", 0, misread.size)

        // ── Direction 2: another app's window is never a payment. ─────────────────────────────────
        val foreign = live.filter { s -> foreignChrome.any { s.text.contains(it) } }
        assertTrue("no foreign-app screens in the corpus — the labels must have gone stale", foreign.isNotEmpty())
        val foreignKept = foreign.filter { verdict(it) != QualifyVerdict.REJECTED }
        assertEquals(
            "${foreignKept.size} screens from another app still qualify as payments " +
                "(${foreignKept.joinToString { money(it.amountPaise) }})",
            0, foreignKept.size,
        )

        val rejected = live.filter { verdict(it) == QualifyVerdict.REJECTED }
        val rejectedPaise = rejected.sumOf { it.amountPaise }
        println(
            "corpus: ${screens.size} screens (${live.size} live, ${proven.size} bank-proven) | " +
                "rejected ${rejected.size} = ${money(rejectedPaise)} | lost real: 0",
        )

        // ── Direction 3: a mandate setup may NEVER record — the ₹ on it is the limit, not a charge. ──
        val setups = live.filter { isMandateSetup(it) }
        assertTrue("no mandate-setup screens found — the label went stale", setups.size >= 5)
        val setupsKept = setups.filter { verdict(it) != QualifyVerdict.REJECTED }
        assertEquals(
            "${setupsKept.size} autopay setups still record their LIMIT as a spend " +
                "(${setupsKept.joinToString { money(it.amountPaise) }})",
            0, setupsKept.size,
        )

        // ── Direction 4: a screen REPORTING an outcome is never a new payment. ─────────────────────
        // PhonePe paints "Payment Successful" over its pay sheet and the tree keeps the sheet's "Pay ₹", so
        // the old rules booked the success screen as a second payment.
        val reporting = live.filter { ConfirmSheetPatterns.outcomeHeadline(it.text) != null }
        assertTrue("no outcome-headline screens found — the label went stale", reporting.isNotEmpty())
        val reportingKept = reporting.filter { verdict(it) != QualifyVerdict.REJECTED }
        assertEquals("${reportingKept.size} success/failure screens still record a payment", 0, reportingKept.size)

        // ── Labels: the any-bank rule must not relabel a single existing Google Pay row. ───────────
        // A listed name still wins over the line above "Bank Name", so "HDFC" stays "HDFC" (the account
        // filters match labels exactly). Compared against the label each row was actually stored with.
        val relabelled = live.filter { it.pkg == GpayConfirmSheetParser.PKG && it.bankLabel != null }
            .mapNotNull { s -> parse(s)?.let { p -> s to p.bankLabel } }
            .filter { (s, label) -> label != s.bankLabel }
        assertEquals("${relabelled.size} Google Pay rows would get a different bank label", 0, relabelled.size)

        // ── Pinned baseline for the 2026-08-22 export, so a future loosening shows up as a failure. ──
        // 2026-09-23: 24 → 26 (+₹2.00). The two added rejections are PhonePe success overlays — the
        // "Payment Successful" screen of a ₹1 self-pay (2026-06-23, twice) whose tree still carried the
        // sheet's "Pay ₹1", each booked by the old code as a SECOND row 8 s after the real one. Neither
        // row is bank-proven (no RRN, never merged); the real ₹1 rows they duplicated are untouched.
        if (txns.size == SNAPSHOT_TXNS) {
            assertEquals(
                "the 2026-08-22 corpus: 19 phantoms + 5 mandate setups + 2 PhonePe success overlays",
                26, rejected.size,
            )
            assertEquals("...worth exactly ₹52,718.81", 5_271_881L, rejectedPaise)
            assertEquals("...of which the success overlays are exactly those two", 2, reporting.size)
        }

        // ── Ring safety: the 30s (pkg, amount) dedup ring must not be able to eat a real payment. ──
        // Replay the bank-proven captures in time order: if two of them ever shared an app and an
        // amount within the ring's TTL, the ring would have silently dropped the second — a genuine
        // payment. This turns that from an assumption into a measurement.
        val ringClashes = proven
            .groupBy { it.pkg to it.amountPaise }
            .values
            .flatMap { group ->
                group.map { it.atMs }.sorted().zipWithNext().filter { (a, b) -> b - a < RING_TTL_MS }
            }
        assertEquals(
            "bank-proven payments repeat the same (app, amount) inside the dedup ring's TTL — " +
                "the ring would eat a real payment: $ringClashes",
            0, ringClashes.size,
        )
    }

    /**
     * **Any payer bank, proven on every real sheet layout.** The stored Google Pay sheets are all HDFC /
     * SBI / ICICI, so the corpus alone can never show an unlisted bank being dropped. This swaps each
     * bank-proven sheet's payer-bank line (the line above "Bank Name") for banks NOT in the list and
     * demands the same verdict and amount, with the swapped name as the label. Under the old "one of 14
     * names" rule every one of these was rejected — a whole bank's customers got no Google Pay capture.
     */
    @Test
    fun `every real Google Pay sheet still records when the payer bank is not a listed one`() {
        val corpus = findCorpus()
        assumeTrue("no corpus on this machine — skipping (see the KDoc)", corpus != null)
        val root = JSONObject(corpus!!.readText())
        val txns = root.getJSONArray("transactions").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("id") }
        }
        val rawArr = root.getJSONArray("rawEvents")
        val sheets = (0 until rawArr.length()).map { rawArr.getJSONObject(it) }
            .filter { it.optString("source") == "a11y" && it.optString("packageName") == GpayConfirmSheetParser.PKG }
            .mapNotNull { ev ->
                val txn = txns[ev.optString("txnId")] ?: return@mapNotNull null
                val proven = !txn.isNull("rrn") || txn.optString("source") == "a11y+sms"
                if (!proven || txn.optString("status") == "DISCARDED") return@mapNotNull null
                ev.optString("payload") to txn.optLong("amountPaise")
            }
            .filter { (text, _) -> text.lines().contains("Bank Name") }
        val parser = GpayConfirmSheetParser()
        // Only the sheets that record today with their real (listed) bank: the claim is "a different bank
        // changes nothing", and the bank-proven autopay setups are rejected on purpose whatever the bank.
        val recording = sheets.filter { (text, amount) ->
            parser.qualify(text).let { it.verdict == QualifyVerdict.QUALIFIED && it.amountPaise == amount }
        }
        assertTrue("too few bank-proven Pay sheets (${recording.size}) — the corpus is not the real export",
            recording.size >= 100)

        val banks = listOf("Federal Bank", "Indian Bank", "Bank of India", "IDBI Bank", "AU Small Finance Bank")
        val failures = mutableListOf<String>()
        for ((text, amount) in recording) {
            val lines = text.lines().toMutableList()
            val at = lines.indexOf("Bank Name") - 1
            if (at < 0) continue
            for (bank in banks) {
                lines[at] = bank
                val swapped = lines.joinToString("\n")
                val q = parser.qualify(swapped)
                if (q.verdict != QualifyVerdict.QUALIFIED || q.amountPaise != amount) {
                    failures += "${money(amount)} as $bank: ${q.verdict} (${q.reason})"
                    continue
                }
                // Label check only where no listed name appears elsewhere on the sheet (it would win).
                val label = parser.parse(RawCapture(Source.A11Y, swapped, GpayConfirmSheetParser.PKG, null, null, 0L))
                    ?.bankLabel
                if (!ConfirmSheetPatterns.BANK.containsMatchIn(swapped) && label != bank) {
                    failures += "${money(amount)} as $bank: labelled '$label'"
                }
            }
        }
        assertEquals("unlisted banks lost on real sheets: ${failures.take(5)}", 0, failures.size)
    }

    private data class Screen(
        val pkg: String, val text: String, val amountPaise: Long,
        val status: String, val source: String, val hasRrn: Boolean, val atMs: Long,
        val bankLabel: String? = null,
    )

    private fun qualify(s: Screen) =
        registry.forPackage(s.pkg)?.qualify(s.text)
            ?: com.goushik.upiwallet.parse.QualifyResult(QualifyVerdict.REJECTED, "unknown package")

    private fun parse(s: Screen) =
        registry.forPackage(s.pkg)?.parse(RawCapture(Source.A11Y, s.text, s.pkg, null, null, s.atMs))

    private fun verdict(s: Screen) = qualify(s).verdict

    private fun money(paise: Long) = "₹%,.2f".format(paise / 100.0)

    /** The mandate-approval screen's verbatim headline — see GpayConfirmSheetParser.MANDATE_SETUP. */
    private fun isMandateSetup(s: Screen) = s.text.contains("Setting an AUTOPAY", ignoreCase = true)

    private fun findCorpus(): File? {
        System.getenv("UET_CORPUS")?.let { return File(it).takeIf(File::isFile) }
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            val backups = File(dir, "backups")
            if (backups.isDirectory) {
                backups.listFiles { f: File -> f.isDirectory && f.name.startsWith("DEVICE-") }
                    ?.sortedByDescending { it.name }
                    ?.forEach { snap ->
                        File(snap, "UET-backup-current.json").takeIf(File::isFile)?.let { return it }
                    }
            }
            dir = dir?.parentFile
        }
        return null
    }

    private companion object {
        /** The export this baseline was measured on: 565 transactions, pulled 2026-08-22. */
        const val SNAPSHOT_TXNS = 565

        /** Mirrors [A11yCaptureService.EPISODE_WINDOW_MS] — the ring's TTL. */
        const val RING_TTL_MS = A11yCaptureService.EPISODE_WINDOW_MS
    }
}
