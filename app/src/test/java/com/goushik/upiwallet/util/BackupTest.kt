package com.goushik.upiwallet.util

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.MerchantRuleEntity
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.BalanceCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PURE host unit tests for [Backup] encode/decode — the offline backup that lets a friend's data survive
 * an uninstall. Proves the JSON round-trip is byte-for-byte lossless across all six tables (so restore
 * can't silently drop or corrupt a field) and that the wallet total reconciles before vs. after a
 * round-trip (the "totals must reconcile" guard). Room-level dedup on import is device-verified separately.
 *
 * Uses the real org.json (a `testImplementation` dep) — the android.jar stub would throw.
 */
class BackupTest {

    // A representative snapshot: nullable fields both set and unset, special characters, a self-transfer,
    // a discarded row, a credit, budgets, learned rules, raw events — the messy shape real data takes.
    private fun sampleSnapshot() = Backup.Snapshot(
        transactions = listOf(
            TransactionEntity(
                id = "t1", amountPaise = 34_900, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
                payeeName = "Swiggy, Bengaluru", payeeVpa = "swiggy@okhdfc", payerAccountLast4 = "1234",
                bankLabel = "HDFC", rrn = "412345678901", timestampEvent = 1_000, timestampCaptured = 1_050,
                source = Source.A11Y_SMS, episodeId = "ep-1", needsReview = false, category = "Food",
                categoryConfidence = 0.5f, categorySource = "auto", latRounded = 12.97, lngRounded = 77.59,
            ),
            // a11y-only row: no rrn, no vpa, nulls where the schema allows them
            TransactionEntity(
                id = "t2", amountPaise = 5_000, direction = Direction.DEBIT, status = TxnStatus.PENDING,
                payeeName = "Ramesh", timestampEvent = 2_000, timestampCaptured = 2_000, source = Source.A11Y,
            ),
            // a credit (income) and a discarded row — exercises direction + status coverage
            TransactionEntity(
                id = "t3", amountPaise = 100_000, direction = Direction.CREDIT, status = TxnStatus.CONFIRMED,
                rrn = "999900001111", timestampEvent = 3_000, timestampCaptured = 3_000, source = Source.SMS,
            ),
            TransactionEntity(
                id = "t4", amountPaise = 77_700, direction = Direction.DEBIT, status = TxnStatus.DISCARDED,
                timestampEvent = 4_000, timestampCaptured = 4_000, source = Source.A11Y,
            ),
        ),
        rawEvents = listOf(
            RawEventEntity("r1", "t1", Source.SMS, "VK-HDFCBK", "DEBIT", "Sent Rs.349 to Swiggy... UPI:412345678901", 1_050),
            RawEventEntity("r2", null, Source.A11Y, "com.google.android.apps.nbu.paisa.user", "confirm-sheet", "Pay ₹50", 2_000),
        ),
        anchors = listOf(
            BalanceAnchorEntity("a1", "HDFC", 500_000, 900),
            BalanceAnchorEntity("a2", "SBI", 250_000, 900),
        ),
        profile = UserProfileEntity(
            displayName = "Bram", ownVpasCsv = "bram@oksbi,bram@okhdfc", onboardedAt = 800, showBalance = true,
        ),
        merchantRules = listOf(
            MerchantRuleEntity("swiggy", "Food", "user", 1_100),
            MerchantRuleEntity("ramesh", "Other", "auto", 2_100),
        ),
        budgets = listOf(
            BudgetEntity("MONTH", 2_000_000, 500, lastAlertedThreshold = 80, lastAlertedPeriodStart = 100),
        ),
    )

    private fun roundTrip(snap: Backup.Snapshot): Backup.Snapshot {
        val json = Backup.encode(snap, appVersion = "1.1", dbVersion = 7, exportedAt = 12_345)
        return Backup.decode(json)
    }

    @Test fun `round-trips every table with full fidelity`() {
        val original = sampleSnapshot()
        val restored = roundTrip(original)

        // data classes give structural equality — every field of every row must survive verbatim.
        assertEquals(original.transactions, restored.transactions)
        assertEquals(original.rawEvents, restored.rawEvents)
        assertEquals(original.anchors, restored.anchors)
        assertEquals(original.profile, restored.profile)
        assertEquals(original.merchantRules, restored.merchantRules)
        assertEquals(original.budgets, restored.budgets)
    }

    @Test fun `the wallet total reconciles across a round-trip`() {
        val original = sampleSnapshot()
        val ownVpas = original.profile!!.ownVpaSet()
        val ownNames = original.profile!!.ownNameSet()

        val before = BalanceCalculator.available(original.anchors, original.transactions, ownVpas, ownNames)
        val restored = roundTrip(original)
        val after = BalanceCalculator.available(restored.anchors, restored.transactions, ownVpas, ownNames)

        assertEquals(before, after)
        // Sanity: 750_000 baseline − 34_900 debit + 100_000 credit (t2 PENDING counts, t4 DISCARDED excluded)
        assertEquals(750_000L - 34_900L - 5_000L + 100_000L, after)
    }

    @Test fun `an empty snapshot round-trips to empty`() {
        val empty = Backup.Snapshot(emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())
        val restored = roundTrip(empty)
        assertEquals(0, restored.transactions.size)
        assertEquals(null, restored.profile)
    }

    @Test fun `decode rejects a file that isn't a backup`() {
        assertThrows(IllegalArgumentException::class.java) {
            Backup.decode("""{"hello":"world"}""")
        }
    }

    @Test fun `dbVersionOf reads the schema version from the header`() {
        val json = Backup.encode(sampleSnapshot(), appVersion = "1.1", dbVersion = 7, exportedAt = 0)
        assertEquals(7, Backup.dbVersionOf(json))
        assertNotNull(json)
    }
}
