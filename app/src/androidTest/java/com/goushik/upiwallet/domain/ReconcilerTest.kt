package com.goushik.upiwallet.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.ParserRegistry
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.util.Ids
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
 * (MERGED / FLIPPED / CREDIT_STANDALONE / DEBIT_STANDALONE / DUPLICATE) and the resulting row state — the
 * money-pipeline guarantee that a confirm-sheet + its later SMS never double-count, and that the bank's
 * proof always beats a screen-read heuristic (a late DISCARD can't undo a merge; an SMS flips a wrong one).
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

    // ── bank truth beats the screen ───────────────────────────────────────────────────────────────────

    /** A GPay confirm sheet for [amount] → the PENDING a11y row's id. */
    private suspend fun screenRow(amount: Long, episode: String = "ep-x") = reconciler.onConfirmSheet(
        parsed = debit(amount),
        rawPayload = "confirm sheet payload",
        episodeId = episode,
        pkg = "com.google.android.apps.nbu.paisa.user",
    )

    @Test
    fun aLateScreenDiscardCannotUndoAnSmsMerge() = runBlocking {
        val id = screenRow(30_000)
        assertEquals(SmsOutcome.MERGED, reconciler.onSms(debit(30_000, rrn = "555555555555"), "DEBIT sms", "BANK"))

        // The episode's terminal read comes in after the SMS (the 3-min terminal window spans the ~90 s SMS
        // band) and says "failed" — the bank already proved the payment, so nothing changes.
        reconciler.setStatus(id, TxnStatus.DISCARDED)

        val row = db.transactionDao().byId(id)!!
        assertEquals(TxnStatus.CONFIRMED, row.status)
        assertEquals("555555555555", row.rrn)
        assertEquals(1, db.transactionDao().allOnce().size)
    }

    @Test
    fun anSmsFlipsAWronglyDiscardedScreenRowBackToConfirmed() = runBlocking {
        val id = screenRow(40_000)
        reconciler.setStatus(id, TxnStatus.DISCARDED)
        assertEquals(TxnStatus.DISCARDED, db.transactionDao().byId(id)!!.status)

        val outcome = reconciler.onSms(debit(40_000, rrn = "666666666666"), "DEBIT sms", "BANK")
        assertEquals(SmsOutcome.FLIPPED, outcome)

        val row = db.transactionDao().byId(id)!!
        assertEquals(TxnStatus.CONFIRMED, row.status)
        assertEquals("666666666666", row.rrn)
        assertEquals("the flip must not add a second row", 1, db.transactionDao().allOnce().size)
    }

    @Test
    fun aPaymentToALenderNamedCreditMergesAsADebit() = runBlocking {
        // Scrubbed shape of the real HDFC alert that was booked as INCOME: the payee's registered name
        // ends in "Credit". It must parse as a DEBIT and confirm the screen row, not stand alone.
        val id = screenRow(500_000)
        val body = "Sent Rs.5000.00\nFrom HDFC Bank A/C *1234\nTo ACME India Credit\nOn 01/01/26\n" +
            "Ref 123456789012\nNot You?\nCall 18001234567/SMS BLOCK UPI to 7000000000"
        val parsed = ParserRegistry.default()
            .parse(RawCapture(Source.SMS, body, sender = "VM-HDFCBK-S", capturedAt = System.currentTimeMillis()))
        assertNotNull(parsed)
        assertEquals(Direction.DEBIT, parsed!!.direction)

        assertEquals(SmsOutcome.MERGED, reconciler.onSms(parsed, body, "VM-HDFCBK-S"))
        val rows = db.transactionDao().allOnce()
        assertEquals("one row, no phantom income", 1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(Direction.DEBIT, rows.single().direction)
        assertEquals(TxnStatus.CONFIRMED, rows.single().status)
        assertEquals("123456789012", rows.single().rrn)
    }

    // ── the SMS's own timestamp ──────────────────────────────────────────────────────────────────────

    @Test
    fun aTextHeldForHoursStillMergesWithItsScreenRow() = runBlocking {
        val now = System.currentTimeMillis()
        val paidAt = now - 3 * 60 * 60_000L // paid 3 h ago; the network held the SMS until now
        db.transactionDao().insert(
            TransactionEntity(
                id = Ids.uuid7(), amountPaise = 25_000, direction = Direction.DEBIT, status = TxnStatus.UNCONFIRMED,
                timestampEvent = paidAt, timestampCaptured = paidAt, source = Source.A11Y,
            )
        )

        val outcome = reconciler.onSms(debit(25_000, rrn = "777777777777"), "DEBIT late", "BANK", eventAt = paidAt + 4_000)
        assertEquals(SmsOutcome.MERGED, outcome)
        val rows = db.transactionDao().allOnce()
        assertEquals("a late SMS must not book the payment twice", 1, rows.size)
        assertEquals("the merged row keeps the pay time", paidAt, rows.single().timestampEvent)
        assertEquals("777777777777", rows.single().rrn)
    }

    @Test
    fun aHeldTextClaimsItsOwnScreenRowNotANewerOneOfTheSameAmount() = runBlocking {
        // P1 paid 3 h ago, its SMS held until now; P2 of the same amount paid 2 min ago. The held text must
        // take P1's row, so P2's own text still finds P2's row: two payments, two rows, nothing twice.
        val now = System.currentTimeMillis()
        val p1At = now - 3 * 60 * 60_000L
        val r1 = screenRowAt(20_000, p1At, TxnStatus.UNCONFIRMED)
        val r2 = screenRowAt(20_000, now - 2 * 60_000L, TxnStatus.PENDING)

        assertEquals(SmsOutcome.MERGED,
            reconciler.onSms(debit(20_000, rrn = "121212121212"), "DEBIT held", "BANK", eventAt = p1At + 4_000))
        assertEquals("121212121212", db.transactionDao().byId(r1)!!.rrn)
        assertNull("the newer row is left for its own text", db.transactionDao().byId(r2)!!.rrn)

        assertEquals(SmsOutcome.MERGED, reconciler.onSms(debit(20_000, rrn = "131313131313"), "DEBIT on time", "BANK"))
        assertEquals("131313131313", db.transactionDao().byId(r2)!!.rrn)
        assertEquals("two payments, two rows", 2, db.transactionDao().allOnce().size)
    }

    /** A screen-captured (a11y) debit row at an explicit time — the shape onConfirmSheet writes. */
    private suspend fun screenRowAt(
        amount: Long,
        at: Long,
        status: TxnStatus,
        payee: String? = null,
        bank: String? = null,
        source: String = Source.A11Y,
    ): String {
        val id = Ids.uuid7()
        db.transactionDao().insert(
            TransactionEntity(
                id = id, amountPaise = amount, direction = Direction.DEBIT, status = status,
                payeeName = payee, bankLabel = bank, timestampEvent = at, timestampCaptured = at, source = source,
            )
        )
        return id
    }

    // ── a bank that sends its alert late ─────────────────────────────────────────────────────────────

    @Test
    fun anAlertTheBankSentAnHourLateMergesIntoTheSamePayeesScreenRow() = runBlocking {
        // The screen row went UNCONFIRMED an hour ago; the bank's alert comes now with an on-time network
        // stamp, so the usual windows reach only minutes back. Same payee (the bank cut the name short),
        // same bank → it is that payment, not a second one.
        val now = System.currentTimeMillis()
        val paidAt = now - 57 * 60_000L
        val id = screenRowAt(500_000, paidAt, TxnStatus.UNCONFIRMED, payee = "Kestrel Stores", bank = "State Bank of India")

        val sms = debit(500_000, rrn = "141414141414").copy(payeeName = "KESTREL ST", bankLabel = "SBI")
        assertEquals(SmsOutcome.MERGED, reconciler.onSms(sms, "DEBIT sent late by the bank", "VA-SBIUPI", eventAt = now - 4_000))
        val rows = db.transactionDao().allOnce()
        assertEquals("the late alert must not book the payment twice", 1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(TxnStatus.CONFIRMED, rows.single().status)
        assertEquals("141414141414", rows.single().rrn)
        assertEquals("the merged row keeps the pay time", paidAt, rows.single().timestampEvent)
    }

    @Test
    fun aLateAlertForADifferentPayeeStandsAlone() = runBlocking {
        val now = System.currentTimeMillis()
        val id = screenRowAt(500_000, now - 57 * 60_000L, TxnStatus.UNCONFIRMED, payee = "Kestrel Stores", bank = "SBI")
        val sms = debit(500_000, rrn = "151515151515").copy(payeeName = "MARIGOLD TRADERS", bankLabel = "SBI")
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(sms, "DEBIT other payee", "VA-SBIUPI"))
        assertEquals(2, db.transactionDao().allOnce().size)
        assertNull(db.transactionDao().byId(id)!!.rrn)
    }

    @Test
    fun aLateAlertNeverClaimsAScreenRowPaidFromAnotherBank() = runBlocking {
        val now = System.currentTimeMillis()
        val id = screenRowAt(100, now - 82 * 60_000L, TxnStatus.UNCONFIRMED, payee = "Kestrel Stores", bank = "State Bank of India")
        val sms = debit(100, rrn = "161616161616").copy(payeeName = "Kestrel Stores", bankLabel = "HDFC")
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(sms, "DEBIT from HDFC", "VM-HDFCBK"))
        assertNull("an SBI screen row is not the HDFC debit", db.transactionDao().byId(id)!!.rrn)
    }

    @Test
    fun aLateAlertLooksBackHoursNotDays() = runBlocking {
        val now = System.currentTimeMillis()
        screenRowAt(500_000, now - 4 * 60 * 60_000L, TxnStatus.UNCONFIRMED, payee = "Kestrel Stores", bank = "SBI")
        val sms = debit(500_000, rrn = "171717171717").copy(payeeName = "Kestrel Stores", bankLabel = "SBI")
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(sms, "DEBIT four hours on", "VA-SBIUPI"))
    }

    // ── only screen captures are merge targets ──────────────────────────────────────────────────────

    @Test
    fun aUpiAlertNeverClaimsAnSmsOnlyRow() = runBlocking {
        // A card spend (SMS, no RRN) of ₹500 at 10:00, then a ₹500 UPI payment no screen caught: two payments.
        val card = screenRowAt(50_000, System.currentTimeMillis() - 5 * 60_000L, TxnStatus.CONFIRMED, source = Source.SMS)
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(debit(50_000, rrn = "181818181818"), "DEBIT upi", "BANK"))
        assertEquals("two real payments stay two rows", 2, db.transactionDao().allOnce().size)
        assertNull(db.transactionDao().byId(card)!!.rrn)
        assertEquals(Source.SMS, db.transactionDao().byId(card)!!.source)
    }

    @Test
    fun aUpiAlertNeverClaimsAManualRow() = runBlocking {
        val manual = screenRowAt(50_000, System.currentTimeMillis() - 60_000L, TxnStatus.CONFIRMED, source = Source.MANUAL)
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(debit(50_000, rrn = "191919191919"), "DEBIT upi", "BANK"))
        assertNull(db.transactionDao().byId(manual)!!.rrn)
        assertEquals(Source.MANUAL, db.transactionDao().byId(manual)!!.source)
    }

    @Test
    fun aUpiAlertNeverBringsBackARemovedSmsOnlyRow() = runBlocking {
        val removed = screenRowAt(50_000, System.currentTimeMillis() - 60_000L, TxnStatus.DISCARDED, source = Source.SMS)
        assertEquals(SmsOutcome.DEBIT_STANDALONE, reconciler.onSms(debit(50_000, rrn = "202020202020"), "DEBIT upi", "BANK"))
        assertEquals("what the user removed stays removed", TxnStatus.DISCARDED, db.transactionDao().byId(removed)!!.status)
    }

    @Test
    fun aStandaloneSmsRowIsDatedWhenTheBankSentIt() = runBlocking {
        val held = 7 * 60 * 60_000L // 23:50 → delivered 06:50
        val sentAt = System.currentTimeMillis() - held
        reconciler.onSms(credit(80_000, rrn = "888888888888"), "CREDIT held overnight", "BANK", eventAt = sentAt)
        val row = db.transactionDao().allOnce().single()
        assertEquals(sentAt, row.timestampEvent)
        assertTrue("captured stays the arrival time", row.timestampCaptured >= sentAt + held)
    }
}
