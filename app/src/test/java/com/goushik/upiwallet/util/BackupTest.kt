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

    // ── planAnchorMerge: an account is its LABEL, and restore can never move the cutoff ──

    private fun anchorOf(id: String, label: String, baseline: Long, at: Long) =
        BalanceAnchorEntity(id, label, baseline, at)

    @Test fun `anchors restore verbatim into an empty device`() {
        val incoming = listOf(anchorOf("a1", "HDFC", 500_000, 900), anchorOf("a2", "SBI", 250_000, 900))
        assertEquals(incoming, Backup.planAnchorMerge(emptyList(), incoming))
    }

    @Test fun `a label match skips the incoming anchor - case and trim insensitive`() {
        val existing = listOf(anchorOf("x1", "HDFC", 700_000, 5_000))
        val incoming = listOf(anchorOf("a1", " hdfc ", 500_000, 900), anchorOf("a2", "SBI", 250_000, 900))

        val plan = Backup.planAnchorMerge(existing, incoming)

        assertEquals(listOf("SBI"), plan.map { it.accountLabel })
    }

    @Test fun `a surviving newcomer is re-stamped to the device cutoff so restore only adds its baseline`() {
        val existing = listOf(anchorOf("x1", "HDFC", 700_000, 5_000))
        // Backup exported AFTER the device onboarded — its stamp is newer and would drag the cutoff
        // forward (re-cutting the device account's history) if taken verbatim.
        val incoming = listOf(anchorOf("a2", "ICICI", 250_000, 9_000))
        val txns = listOf(
            TransactionEntity(
                id = "d1", amountPaise = 40_000, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
                timestampEvent = 6_000, timestampCaptured = 6_000, source = Source.A11Y,
            ),
        )
        val before = BalanceCalculator.available(existing, txns, emptySet(), emptySet())

        val merged = existing + Backup.planAnchorMerge(existing, incoming)

        assertEquals(before + 250_000L, BalanceCalculator.available(merged, txns, emptySet(), emptySet()))
    }

    @Test fun `restoring the same anchors twice adds nothing`() {
        val incoming = listOf(anchorOf("a1", "HDFC", 500_000, 900))
        val afterFirst = Backup.planAnchorMerge(emptyList(), incoming)
        assertEquals(emptyList<BalanceAnchorEntity>(), Backup.planAnchorMerge(afterFirst, incoming))
    }

    @Test fun `label duplicates inside the backup itself collapse to one`() {
        val incoming = listOf(anchorOf("a1", "HDFC", 500_000, 900), anchorOf("a2", "hdfc", 100_000, 901))
        assertEquals(listOf("a1"), Backup.planAnchorMerge(emptyList(), incoming).map { it.id })
    }

    // ── planTxnMerge: the same payment never lands twice, a genuine repeat always survives ──

    private fun txnOf(
        id: String, paise: Long, ts: Long,
        rrn: String? = null, vpa: String? = null, name: String? = null,
        direction: Direction = Direction.DEBIT, status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = id, amountPaise = paise, direction = direction, status = status,
        payeeName = name, payeeVpa = vpa, rrn = rrn,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )

    @Test fun `an already-present id is skipped`() {
        val row = txnOf("t1", 5_000, 1_000)
        val plan = Backup.planTxnMerge(listOf(row), listOf(row.copy(payeeName = "edited later")))
        assertEquals(0, plan.toInsert.size)
        assertEquals(1, plan.skipped)
    }

    @Test fun `a matching rrn and direction is skipped even under a fresh id`() {
        val existing = txnOf("t1", 5_000, 1_000, rrn = "412345678901")
        val incoming = txnOf("b1", 5_000, 999_999, rrn = "412345678901")
        assertEquals(0, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `a NULL-rrn capture with identical content is skipped under a fresh id`() {
        // The real duplication bug: a11y rows carry no RRN, and the same payment captured live and
        // restored from a backup arrives under two different UUIDs.
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val incoming = txnOf("b1", 5_000, 1_000, name = "Ramesh")
        assertEquals(0, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `a near-in-time copy to the same payee is skipped within the 3-minute window`() {
        val existing = txnOf("t1", 5_000, 1_000, vpa = "ramesh@oksbi")
        val incoming = txnOf("b1", 5_000, 1_000 + 2 * 60_000, vpa = "ramesh@okaxis") // same localpart
        assertEquals(0, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `a same-amount payment to a DIFFERENT payee survives`() {
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val incoming = txnOf("b1", 5_000, 1_500, name = "Suresh")
        assertEquals(1, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `a windowed match without BOTH payees resolved survives`() {
        // Silently dropping a genuine payment is the worse failure — a missing payee never skips.
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val incoming = txnOf("b1", 5_000, 1_500) // no vpa, no name
        assertEquals(1, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `the same payee outside the 3-minute window survives - chai twice is two payments`() {
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val incoming = txnOf("b1", 5_000, 1_000 + 10 * 60_000, name = "Ramesh")
        assertEquals(1, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `a near-identical row with a DIFFERENT rrn survives - the bank says they're distinct`() {
        // The device-verified shape: two ₹1 credits 2 min apart, same payee, different RRNs (made-up values).
        val existing = txnOf("t1", 100, 1_000, rrn = "111111111111", vpa = "someone@ybl", direction = Direction.CREDIT)
        val incoming = txnOf("b1", 100, 1_000 + 2 * 60_000, rrn = "222222222222", vpa = "someone@ybl", direction = Direction.CREDIT)
        assertEquals(1, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `an rrn-bearing row never content-skips even at the exact same timestamp`() {
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val incoming = txnOf("b1", 5_000, 1_000, rrn = "412345678901", name = "Ramesh")
        assertEquals(1, Backup.planTxnMerge(listOf(existing), listOf(incoming)).toInsert.size)
    }

    @Test fun `two no-rrn payments kept apart in the backup both come back onto an empty phone`() {
        // Two sub-₹100 payments to one stall two minutes apart: no bank SMS, so no RRN. The ledger that
        // was backed up held both; restoring must give back exactly that (it used to drop the second
        // as a "twin" of the first and say "1 already here" on an empty phone).
        val a = txnOf("b1", 5_000, 1_000, name = "ACME Stores")
        val b = txnOf("b2", 5_000, 1_000 + 2 * 60_000, name = "ACME Stores")
        val plan = Backup.planTxnMerge(emptyList(), listOf(a, b))
        assertEquals(listOf("b1", "b2"), plan.toInsert.map { it.id })
        assertEquals(0, plan.skipped)
    }

    @Test fun `even identical content under two ids in one file is two rows - the file already kept them apart`() {
        val a = txnOf("b1", 5_000, 1_000, name = "Ramesh Kumar")
        val b = txnOf("b2", 5_000, 1_000, name = "Ramesh Kumar")
        assertEquals(2, Backup.planTxnMerge(emptyList(), listOf(a, b)).toInsert.size)
    }

    @Test fun `a doubled file still collapses - the id check runs across the file`() {
        val a = txnOf("b1", 5_000, 1_000, name = "Ramesh Kumar")
        val plan = Backup.planTxnMerge(emptyList(), listOf(a, a.copy()))
        assertEquals(1, plan.toInsert.size)
        assertEquals(1, plan.skipped)
    }

    @Test fun `the same rrn twice in one file collapses - the rrn check runs across the file`() {
        val a = txnOf("b1", 5_000, 1_000, rrn = "333333333333")
        val b = txnOf("b2", 5_000, 9_000, rrn = "333333333333")
        assertEquals(1, Backup.planTxnMerge(emptyList(), listOf(a, b)).toInsert.size)
    }

    @Test fun `the phone's copy still blocks every same-file twin of it`() {
        // Content tiers still run against the phone: both backup rows match the phone's one payment.
        val phone = txnOf("t1", 5_000, 1_000, name = "ACME Stores")
        val a = txnOf("b1", 5_000, 1_000, name = "ACME Stores")
        val b = txnOf("b2", 5_000, 1_000 + 60_000, name = "ACME Stores")
        assertEquals(0, Backup.planTxnMerge(listOf(phone), listOf(a, b)).toInsert.size)
    }

    @Test fun `skipped counts the rows not inserted`() {
        val existing = txnOf("t1", 5_000, 1_000, name = "Ramesh")
        val fresh = txnOf("b1", 9_000, 2_000, name = "Suresh")
        val plan = Backup.planTxnMerge(listOf(existing), listOf(existing, fresh))
        assertEquals(listOf("b1"), plan.toInsert.map { it.id })
        assertEquals(1, plan.skipped)
    }
}
