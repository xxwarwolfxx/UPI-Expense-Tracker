package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * PURE host tests for the parts of [Backup] that decide what happens to the ONE file that survives an
 * uninstall, and what a restore is allowed to change: which file is ours to replace, when a snapshot must
 * not replace it, which file a release build refuses, whose identity wins, which raw events come along,
 * and what the toasts tell the user. The MediaStore I/O around these is device-only.
 */
class BackupSafetyTest {

    // ── file naming: only MediaStore's own rename shape is ours ──

    @Test fun `the release file and MediaStore's numbered renames are ours`() {
        val n = Backup.RELEASE_NAMING
        assertEquals("UET-backup.json", n.fileName)
        assertTrue(n.isOwnShape("UET-backup.json"))
        assertTrue(n.isOwnShape("UET-backup (1).json"))
        assertTrue(n.isOwnShape("UET-backup (12).json"))
    }

    @Test fun `a copy the user renamed to keep is never ours`() {
        val n = Backup.RELEASE_NAMING
        assertFalse(n.isOwnShape("UET-backup-before-1.1.7.json"))
        assertFalse(n.isOwnShape("UET-backup-20260719.json"))
        assertFalse(n.isOwnShape("UET-backup (2) (1).json"))
        assertFalse(n.isOwnShape("UET-backup (x).json"))
        assertFalse(n.isOwnShape("UET-backup.json.bak"))
        assertFalse(n.isOwnShape("my UET-backup.json"))
    }

    @Test fun `the never-shrink dated file is not in the replaceable shape`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val at = ZonedDateTime.of(2026, 9, 23, 15, 30, 0, 0, zone).toInstant().toEpochMilli()
        val dated = Backup.RELEASE_NAMING.datedName(at, zone)
        assertEquals("UET-backup-2026-09-23-1530.json", dated)
        assertFalse(Backup.RELEASE_NAMING.isOwnShape(dated))
    }

    @Test fun `the test copy writes an unmistakable name and neither build matches the other's files`() {
        val test = Backup.naming(testCopy = true)
        val real = Backup.naming(testCopy = false)
        assertEquals("UET-TEST-COPY-backup.json", test.fileName)
        assertTrue(test.isOwnShape("UET-TEST-COPY-backup (3).json"))
        assertFalse(test.isOwnShape("UET-backup.json"))
        assertFalse(test.isOwnShape("UET-backup (3).json"))
        assertFalse(real.isOwnShape("UET-TEST-COPY-backup.json"))
        // The LIKE pre-filter can't pull the other build's files in either.
        assertFalse("UET-TEST-COPY-backup.json".startsWith(real.likePattern.removeSuffix("%.json")))
    }

    // ── never shrink: a snapshot far smaller than the last backup must not replace it ──

    @Test fun `an empty snapshot never replaces a backup that has payments`() {
        assertTrue(Backup.shouldKeepPrevious(newCount = 0, previousCount = 565))
        assertTrue(Backup.shouldKeepPrevious(newCount = 0, previousCount = 1))
    }

    @Test fun `a snapshot under half the last backup is kept apart`() {
        // The debug seeder: a ledger of hundreds replaced by 31 sample rows.
        assertTrue(Backup.shouldKeepPrevious(newCount = 31, previousCount = 714))
        assertTrue(Backup.shouldKeepPrevious(newCount = 282, previousCount = 565))
    }

    @Test fun `normal growth and a small cleanup replace as usual`() {
        assertFalse(Backup.shouldKeepPrevious(newCount = 566, previousCount = 565))
        assertFalse(Backup.shouldKeepPrevious(newCount = 546, previousCount = 565))
        assertFalse(Backup.shouldKeepPrevious(newCount = 283, previousCount = 565)) // exactly half-ish stays normal
    }

    @Test fun `with nothing to compare against, the write goes ahead`() {
        assertFalse(Backup.shouldKeepPrevious(newCount = 0, previousCount = null))
        assertFalse(Backup.shouldKeepPrevious(newCount = 0, previousCount = 0))
    }

    @Test fun `countTransactions reads a backup's size and rejects a broken file`() {
        val json = Backup.encode(snapshotOf(txnOf("t1", 5_000, 1_000), txnOf("t2", 6_000, 2_000)), "1.1.7", 7, 0)
        assertEquals(2, Backup.countTransactions(json))
        assertNull(Backup.countTransactions("{\"format\":1,\"transactions\":[{\"id\""))
        assertEquals(0, Backup.countTransactions("{\"format\":1}"))
    }

    // ── same ledger: only a later state of the same ledger replaces (or tidies away) a backup ──

    private fun backupOf(vararg ids: String): String =
        Backup.encode(snapshotOf(*ids.mapIndexed { i, id -> txnOf(id, 1_000L * (i + 1), 1_000L * (i + 1)) }.toTypedArray()), "1.1.7", 7, 0)

    @Test fun `a later state of the same ledger replaces the backup`() {
        val previous = backupOf("t1", "t2", "t3")
        assertTrue(Backup.mayReplace(previous, setOf("t1", "t2", "t3")))
        assertTrue(Backup.mayReplace(previous, setOf("t1", "t2", "t3", "t4")))   // grew
        assertTrue(Backup.mayReplace(backupOf(), setOf("t1")))                   // an empty backup holds nothing to lose
    }

    @Test fun `a different ledger never replaces the backup, however big it has grown`() {
        // The database was recreated in place (corruption, then a fresh setup). The new ledger passes the
        // count rule long before it holds any of the old payments.
        val previous = backupOf("t1", "t2", "t3", "t4")
        val newLedger = setOf("n1", "n2", "n3")
        assertFalse(Backup.shouldKeepPrevious(newCount = newLedger.size, previousCount = 4))  // count rule alone: replace
        assertFalse(Backup.mayReplace(previous, newLedger))                                 // same-ledger rule: keep
        assertFalse(Backup.mayReplace(previous, setOf("t1", "t2", "t3", "n1", "n2")))       // one old payment missing
    }

    @Test fun `a file that reads but is not a backup may go, one that could not be read stays`() {
        assertTrue(Backup.mayReplace("{\"format\":1,\"transactions\":[{\"id\"", setOf("t1")))  // cut short
        assertFalse(Backup.mayReplace(null, setOf("t1")))                                    // unread this time
    }

    @Test fun `transactionIds reads every id and rejects a broken file`() {
        assertEquals(setOf("t1", "t2"), Backup.transactionIds(backupOf("t1", "t2")))
        assertEquals(emptySet<String>(), Backup.transactionIds("{\"format\":1}"))
        assertNull(Backup.transactionIds("not json"))
    }

    // ── header + refusal ──

    @Test fun `the header carries the app version and when the backup was made`() {
        val json = Backup.encode(snapshotOf(), appVersion = "1.1.7-debug", dbVersion = 7, exportedAt = 1_234_567)
        assertEquals(Backup.Header("1.1.7-debug", 7, 1_234_567L), Backup.header(json))
    }

    @Test fun `a release build refuses the test copy's file`() {
        val msg = Backup.restoreRefusal(Backup.Header("1.1.7-debug", 7, 0), currentDbVersion = 7, thisIsTestCopy = false)
        assertTrue(msg!!.contains("test copy"))
    }

    @Test fun `the test copy may restore the real app's file, and the real app its own`() {
        assertNull(Backup.restoreRefusal(Backup.Header("1.1.6", 7, 0), 7, thisIsTestCopy = true))
        assertNull(Backup.restoreRefusal(Backup.Header("1.1.7-debug", 7, 0), 7, thisIsTestCopy = true))
        assertNull(Backup.restoreRefusal(Backup.Header("1.1.6", 7, 0), 7, thisIsTestCopy = false))
        assertNull(Backup.restoreRefusal(Backup.Header(null, 7, 0), 7, thisIsTestCopy = false))
    }

    @Test fun `a file from a newer schema is refused`() {
        val msg = Backup.restoreRefusal(Backup.Header("2.0", 8, 0), currentDbVersion = 7, thisIsTestCopy = false)
        assertTrue(msg!!.contains("newer version"))
    }

    // ── raw events: nothing left pointing at a skipped payment ──

    @Test fun `a raw event whose payment was skipped is left out, the rest come along`() {
        val raws = listOf(
            raw("r1", "onPhone"),
            raw("r2", "inserted"),
            raw("r3", "skippedDuplicate"),
            raw("r4", null), // a screen capture turned down: evidence, kept
        )
        val kept = Backup.planRawMerge(setOf("onPhone"), setOf("inserted"), raws)
        assertEquals(listOf("r1", "r2", "r4"), kept.map { it.id })
    }

    // ── identity: the phone's own name and UPI IDs win ──

    private val phoneProfile = UserProfileEntity(
        displayName = "Ramesh Kumar", ownVpasCsv = "ramesh@okaxis", onboardedAt = 5_000, showBalance = false,
    )
    private val fileProfile = UserProfileEntity(
        displayName = "Sample Person", ownVpasCsv = "sample@oksbi,sample@okhdfcbank", onboardedAt = 900, showBalance = true,
    )

    @Test fun `restoring an old file keeps the phone's name, UPI IDs, mode and onboarding`() {
        val m = Backup.planProfileMerge(phoneProfile, fileProfile, holdOnboarding = false)
        assertEquals(phoneProfile, m.profile)
        assertTrue(m.keptName)
        assertTrue(m.keptUpiIds)
    }

    @Test fun `empty identity fields on the phone are filled from the file`() {
        val blank = phoneProfile.copy(displayName = "", ownVpasCsv = "")
        val m = Backup.planProfileMerge(blank, fileProfile, holdOnboarding = false)
        assertEquals("Sample Person", m.profile!!.displayName)
        assertEquals(fileProfile.ownVpasCsv, m.profile!!.ownVpasCsv)
        assertEquals(5_000L, m.profile!!.onboardedAt)
        assertFalse(m.profile!!.showBalance)
        assertFalse(m.keptName)
        assertFalse(m.keptUpiIds)
    }

    @Test fun `a phone with a name but no UPI IDs keeps the name and takes the IDs`() {
        val m = Backup.planProfileMerge(phoneProfile.copy(ownVpasCsv = ""), fileProfile, holdOnboarding = false)
        assertEquals("Ramesh Kumar", m.profile!!.displayName)
        assertEquals(fileProfile.ownVpasCsv, m.profile!!.ownVpasCsv)
        assertTrue(m.keptName)
        assertFalse(m.keptUpiIds)
    }

    @Test fun `the same identity in the file isn't reported as kept`() {
        val m = Backup.planProfileMerge(phoneProfile, phoneProfile.copy(displayName = " ramesh kumar "), false)
        assertFalse(m.keptName)
        assertFalse(m.keptUpiIds)
    }

    @Test fun `an existing onboardedAt is never cleared, even when holding onboarding`() {
        val m = Backup.planProfileMerge(phoneProfile, fileProfile.copy(onboardedAt = null), holdOnboarding = true)
        assertEquals(5_000L, m.profile!!.onboardedAt)
    }

    @Test fun `a Welcome restore takes the file's profile whole but holds onboarding open`() {
        val m = Backup.planProfileMerge(null, fileProfile, holdOnboarding = true)
        assertEquals(fileProfile.copy(onboardedAt = null), m.profile)
    }

    @Test fun `a Welcome restore of a file with no profile still marks the restore in progress`() {
        val m = Backup.planProfileMerge(null, null, holdOnboarding = true, showBalanceIfNew = true)
        assertEquals(UserProfileEntity(displayName = "", ownVpasCsv = "", onboardedAt = null, showBalance = true), m.profile)
    }

    @Test fun `no profile in the file leaves the phone's row alone`() {
        assertNull(Backup.planProfileMerge(phoneProfile, null, holdOnboarding = false).profile)
        assertNull(Backup.planProfileMerge(null, null, holdOnboarding = false).profile)
    }

    // ── what the user is told ──

    private val label: (Long) -> String = { mapOf(10L to "14 Jul", 9L to "13 Jul")[it] ?: "?" }

    private fun result(added: Int, skipped: Int, keptName: Boolean = false, keptIds: Boolean = false) =
        Backup.RestoreResult(
            transactionsAdded = added, transactionsSkipped = skipped, rawEventsAdded = 0,
            anchorsAdded = 0, anchorsSkipped = 0, profileApplied = true, merchantRulesApplied = 0,
            budgetsApplied = 0, keptName = keptName, keptUpiIds = keptIds, exportedAt = 10L, newestPaymentAt = 9L,
        )

    @Test fun `the restore toast says how old the backup and its newest payment are`() {
        assertEquals(
            "Restored 296 payments from a backup made 14 Jul, newest payment 13 Jul.",
            Backup.restoreSummary(result(296, 0), label),
        )
    }

    @Test fun `the restore toast counts what was already here and says the identity was kept`() {
        assertEquals(
            "Restored 1 payment from a backup made 14 Jul, newest payment 13 Jul. 4 were already on this phone." +
                " Kept your current name and UPI IDs.",
            Backup.restoreSummary(result(1, 4, keptName = true, keptIds = true), label),
        )
    }

    @Test fun `a restore that adds nothing still names the file's age and what was kept`() {
        assertEquals(
            "Nothing new to add: every payment in a backup made 14 Jul, newest payment 13 Jul is already on " +
                "this phone. Kept your current UPI IDs.",
            Backup.restoreSummary(result(0, 12, keptIds = true), label),
        )
    }

    @Test fun `the saved toast names the real file, and the kept one when the backup would have shrunk`() {
        assertEquals("Backup saved to Downloads/UET-backup (2).json", Backup.savedMessage("UET-backup (2).json", null))
        assertTrue(
            Backup.savedMessage("UET-backup-2026-09-23-1530.json", "UET-backup (2).json")
                .contains("older UET-backup (2).json"),
        )
    }

    @Test fun `the newest payment ignores removed rows`() {
        val snap = snapshotOf(
            txnOf("t1", 5_000, 1_000),
            txnOf("t2", 5_000, 9_000, status = TxnStatus.DISCARDED),
        )
        assertEquals(1_000L, Backup.newestPaymentAt(snap))
        assertNull(Backup.newestPaymentAt(snapshotOf()))
    }

    // ── the owner's real backup, read at test time only (never copied into the repo) ──

    @Test fun `restoring the real backup onto an empty phone gives back every payment it holds`() {
        val file = System.getenv("UET_CORPUS")?.let { File(it) }?.takeIf { it.isFile }
        assumeTrue("UET_CORPUS not set", file != null)
        val snap = Backup.decode(file!!.readText())
        assertTrue(snap.transactions.size > 100)

        val plan = Backup.planTxnMerge(emptyList(), snap.transactions)
        assertEquals(snap.transactions.size, plan.toInsert.size)
        assertEquals(0, plan.skipped)
        // Restoring the same file again adds nothing: every row is now on the phone.
        assertEquals(0, Backup.planTxnMerge(plan.toInsert, snap.transactions).toInsert.size)
    }

    // ── helpers ──

    private fun txnOf(
        id: String, paise: Long, ts: Long, status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = Direction.DEBIT, status = status,
        payeeName = "ACME Stores", timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )

    private fun raw(id: String, txnId: String?) =
        RawEventEntity(id, txnId, Source.A11Y, "com.example.pay", "confirm-sheet", "Pay ₹50", 1_000)

    private fun snapshotOf(vararg txns: TransactionEntity) =
        Backup.Snapshot(txns.toList(), emptyList(), emptyList(), null, emptyList(), emptyList())
}
