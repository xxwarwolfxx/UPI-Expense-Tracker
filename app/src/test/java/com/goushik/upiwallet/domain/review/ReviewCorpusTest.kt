package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The review detectors measured on the owner's REAL ledger — the same export CaptureCorpusTest replays
 * (found the same way: `UET_REVIEW_CORPUS`, else `UET_CORPUS`, else the newest `backups/DEVICE-*` snapshot
 * walking up from the worktree). The corpus is never in git; this test reads it at run time only and
 * prints counts, never names. It skips when the corpus is absent, so it also asserts the corpus size —
 * a silently-empty parse must not look like a pass.
 *
 * Ground truth for the sample rows is independent of the fingerprint: the seeder wrote the profile's
 * `onboardedAt` as its own `now` and dated every fixture before it, and no real capture predates
 * onboarding. So "seeded" must equal exactly "dated before onboardedAt" — no more, no less.
 */
class ReviewCorpusTest {

    @Test
    fun `sample fingerprint finds exactly the seeder's rows and no real payment`() {
        val c = load() ?: return
        val found = SampleRows.find(c.txns).map { it.id }.toSet()
        val onboardedAt = c.onboardedAt
        if (onboardedAt != null) {
            val beforeOnboarding = c.txns.filter { it.timestampEvent < onboardedAt }.map { it.id }.toSet()
            val realFound = c.txns.count { it.id in found && it.timestampEvent >= onboardedAt }
            println("sample rows: fingerprint=${found.size}, dated-before-onboarding=${beforeOnboarding.size}, " +
                "real rows matched=$realFound, profile sample IDs=${SampleRows.sampleIdsIn(c.ownVpasCsv).size}")
            assertEquals("a real payment (dated after onboarding) matched the sample fingerprint", 0, realFound)
            assertEquals("the fingerprint must find exactly the rows the seeder dated before onboarding",
                beforeOnboarding, found)
        }
        if (c.txns.size == SNAPSHOT_TXNS) {
            assertEquals("the 2026-08-22 export holds 31 seeded rows", 31, found.size)
            assertEquals("…all 31 still live", 31, c.txns.count { it.id in found && it.status != TxnStatus.DISCARDED })
            assertEquals("…and both sample UPI IDs in the profile", 2, SampleRows.sampleIdsIn(c.ownVpasCsv).size)
        }
    }

    @Test
    fun `backed-out detector never flags a bank-proven row`() {
        val c = load() ?: return
        val flags = BackedOutDetection.flag(c.txns, c.appOf, now = Long.MAX_VALUE)
        println("backed-out: ${flags.size} flagged of ${c.txns.size} rows, " +
            "${flags.count { it.next.rrn != null }} followed by a bank-proven payment")
        assertTrue("a flagged row carries a bank reference", flags.none { it.row.rrn != null })
        assertTrue("a flagged row was merged with a bank SMS", flags.none { it.row.source.contains("sms") })
        assertTrue("a flagged row's follower came from another app",
            flags.all { c.appOf[it.next.id] == c.appOf[it.row.id] })
        if (c.txns.size == SNAPSHOT_TXNS) {
            assertEquals("the 2026-08-22 export: 10 backed-out screens", 10, flags.size)
        }
    }

    private class Corpus(
        val txns: List<TransactionEntity>,
        val appOf: Map<String, String>,
        val onboardedAt: Long?,
        val ownVpasCsv: String?,
    )

    private fun load(): Corpus? {
        val file = findCorpus()
        assumeTrue("no corpus on this machine — skipping (see the KDoc)", file != null)
        val root = JSONObject(file!!.readText())
        val arr = root.getJSONArray("transactions")
        val txns = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TransactionEntity(
                id = o.getString("id"),
                amountPaise = o.getLong("amountPaise"),
                direction = Direction.valueOf(o.getString("direction")),
                status = TxnStatus.valueOf(o.getString("status")),
                payeeName = o.optStringOrNull("payeeName"),
                payeeVpa = o.optStringOrNull("payeeVpa"),
                rrn = o.optStringOrNull("rrn"),
                timestampEvent = o.getLong("timestampEvent"),
                timestampCaptured = o.getLong("timestampCaptured"),
                source = o.getString("source"),
                needsReview = o.optBoolean("needsReview", false),
            )
        }.sortedByDescending { it.timestampEvent }
        val raws = root.getJSONArray("rawEvents")
        val appOf = HashMap<String, String>()
        for (i in 0 until raws.length()) {
            val r = raws.getJSONObject(i)
            if (r.optString("source") != "a11y") continue
            val txnId = r.optStringOrNull("txnId") ?: continue
            val pkg = r.optStringOrNull("packageName") ?: continue
            appOf.putIfAbsent(txnId, pkg)
        }
        val profile = root.optJSONObject("profile")
        assertTrue("corpus parsed to ${txns.size} rows — too few to be the real export", txns.size >= 100)
        return Corpus(
            txns, appOf,
            onboardedAt = profile?.takeUnless { it.isNull("onboardedAt") }?.getLong("onboardedAt"),
            ownVpasCsv = profile?.optStringOrNull("ownVpasCsv"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun findCorpus(): File? {
        for (env in listOf("UET_REVIEW_CORPUS", "UET_CORPUS")) {
            System.getenv(env)?.let { return File(it).takeIf(File::isFile) }
        }
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
        /** The 2026-08-22 export the pinned numbers were measured on (565 transactions). */
        const val SNAPSHOT_TXNS = 565
    }
}
