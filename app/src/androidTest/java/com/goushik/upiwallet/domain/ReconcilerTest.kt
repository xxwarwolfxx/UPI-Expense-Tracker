package com.goushik.upiwallet.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.parse.ParsedTxn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (on-device) test of the async-dedup [Reconciler]: the SmsOutcome matrix
 * (MERGED / CREDIT_STANDALONE / DEBIT_STANDALONE / DUPLICATE) and the resulting row state — the
 * money-pipeline guarantee that a confirm-sheet + its later SMS never double-count.
 *
 * Each test gets a fresh in-memory Room DB (@Before per method); suspend funcs run via runBlocking.
 */
@RunWith(AndroidJUnit4::class)
class ReconcilerTest {

    private lateinit var db: AppDatabase
    private lateinit var reconciler: Reconciler

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        reconciler = Reconciler(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private fun debit(amountPaise: Long, rrn: String? = null) = ParsedTxn(
        amountPaise = amountPaise,
        direction = Direction.DEBIT,
        rrn = rrn,
        parserName = "test",
        parserVersion = 1,
    )

    private fun credit(amountPaise: Long, rrn: String? = null) = ParsedTxn(
        amountPaise = amountPaise,
        direction = Direction.CREDIT,
        rrn = rrn,
        parserName = "test",
        parserVersion = 1,
    )

    // ── CREDIT_STANDALONE ─────────────────────────────────────────────────────────

    @Test
    fun creditSmsBecomesStandaloneConfirmedRow() = runBlocking {
        val outcome = reconciler.onSms(credit(70_000, rrn = "111111111111"), "CREDIT body", "BANK")
        assertEquals(SmsOutcome.CREDIT_STANDALONE, outcome)

        val rows = db.transactionDao().allOnce()
        assertEquals("exactly one row", 1, rows.size)
        val row = rows.single()
        assertEquals(Direction.CREDIT, row.direction)
        assertEquals(TxnStatus.CONFIRMED, row.status)
        assertEquals(70_000L, row.amountPaise)
    }

    // ── DEBIT_STANDALONE (no RRN) ───────────────────────────────────────────────────

    @Test
    fun debitSmsWithoutRrnIsStandalone() = runBlocking {
        val outcome = reconciler.onSms(debit(12_300, rrn = null), "DEBIT no-rrn body", "BANK")
        assertEquals(SmsOutcome.DEBIT_STANDALONE, outcome)

        val rows = db.transactionDao().allOnce()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals(TxnStatus.CONFIRMED, row.status)
        assertEquals(12_300L, row.amountPaise)
        assertNull("no-RRN debit row carries no rrn", row.rrn)
    }

    // ── DEBIT_STANDALONE (RRN but no prior a11y row) ────────────────────────────────

    @Test
    fun debitSmsWithRrnButNoA11yIsStandalone() = runBlocking {
        val outcome = reconciler.onSms(debit(45_600, rrn = "222222222222"), "DEBIT rrn body", "BANK")
        assertEquals(SmsOutcome.DEBIT_STANDALONE, outcome)

        val rows = db.transactionDao().allOnce()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals(TxnStatus.CONFIRMED, row.status)
        assertEquals(45_600L, row.amountPaise)
        assertEquals("222222222222", row.rrn)
    }

    // ── DUPLICATE ───────────────────────────────────────────────────────────────────

    @Test
    fun secondIdenticalDebitSmsIsDuplicate() = runBlocking {
        val rrn = "333333333333"
        val first = reconciler.onSms(debit(99_900, rrn = rrn), "DEBIT body 1", "BANK")
        // No prior a11y row → the first authoritative SMS stands alone.
        assertEquals(SmsOutcome.DEBIT_STANDALONE, first)

        val second = reconciler.onSms(debit(99_900, rrn = rrn), "DEBIT body 2 (redelivered)", "BANK")
        assertEquals(SmsOutcome.DUPLICATE, second)

        // Still exactly ONE txn row of that amount — the duplicate did not insert a second.
        val matching = db.transactionDao().allOnce().filter { it.amountPaise == 99_900L }
        assertEquals("a redelivered SMS must not double-count", 1, matching.size)
        assertEquals(TxnStatus.CONFIRMED, matching.single().status)
        assertEquals(rrn, matching.single().rrn)
    }

    // ── MERGED ───────────────────────────────────────────────────────────────────────

    @Test
    fun a11yPendingThenMatchingSmsMergesIntoOneConfirmedRow() = runBlocking {
        val amount = 50_000L
        val rrn = "444444444444"

        // 1) a11y confirm sheet → a PENDING debit row, no rrn yet.
        val id = reconciler.onConfirmSheet(
            parsed = debit(amount),
            rawPayload = "confirm sheet payload",
            episodeId = "ep-1",
            pkg = "com.google.android.apps.nbu.paisa.user",
        )
        val pending = db.transactionDao().byId(id)
        assertNotNull("a11y row exists", pending)
        assertEquals(TxnStatus.PENDING, pending!!.status)
        assertNull("a11y row has no rrn yet", pending.rrn)

        // 2) the bank SMS for the same amount, carrying the rrn, arrives → MERGE.
        val outcome = reconciler.onSms(debit(amount, rrn = rrn), "DEBIT sms with rrn", "BANK")
        assertEquals(SmsOutcome.MERGED, outcome)

        // Exactly ONE row total (merge updates the existing a11y row, never inserts a second).
        val rows = db.transactionDao().allOnce()
        assertEquals("merge must not create a second row", 1, rows.size)

        // That same row is now CONFIRMED with the rrn stamped on it.
        val merged = db.transactionDao().byId(id)
        assertNotNull(merged)
        assertEquals(amount, merged!!.amountPaise)
        assertEquals(TxnStatus.CONFIRMED, merged.status)
        assertEquals("the SMS rrn is stamped onto the merged row", rrn, merged.rrn)
        assertTrue("no double-count: only one row of this amount",
            rows.count { it.amountPaise == amount } == 1)
    }
}
