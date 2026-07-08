package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PURE host unit tests for [BalanceCalculator] — the wallet's glance number and its self-transfer
 * definition. Covers the available() ledger math, the DISCARDED exclusion, the PENDING-counts rule,
 * and the re-anchor CUTOFF that guards against double-counting history on "Update balance".
 */
class BalanceCalculatorTest {

    private val ownVpas = setOf("bram@oksbi", "bram@okhdfc")
    private val ownNames = setOf("bram")

    // ── available(): baseline + debit/credit ledger ─────────────────────────────

    @Test fun `baseline minus debits plus credits over a single anchor`() {
        val anchors = listOf(anchor(baseline = 100_000, anchoredAt = 1_000))
        val txns = listOf(
            debit(1, 30_000, ts = 2_000),
            debit(2, 20_000, ts = 3_000),
            credit(3, 50_000, ts = 4_000),
        )
        // 100_000 − (30_000 + 20_000) + 50_000 = 100_000
        assertEquals(100_000L, BalanceCalculator.available(anchors, txns, ownVpas, ownNames))
    }

    @Test fun `no txns leaves the summed baseline untouched`() {
        val anchors = listOf(
            anchor(baseline = 100_000, anchoredAt = 1_000),
            anchor(baseline = 250_000, anchoredAt = 1_000),
        )
        // Σ baselines, nothing to subtract.
        assertEquals(350_000L, BalanceCalculator.available(anchors, emptyList(), ownVpas, ownNames))
    }

    @Test fun `DISCARDED rows are excluded from the ledger`() {
        val anchors = listOf(anchor(baseline = 100_000, anchoredAt = 1_000))
        val txns = listOf(
            debit(1, 40_000, ts = 2_000),                                  // counts
            debit(2, 99_000, ts = 2_000, status = TxnStatus.DISCARDED),    // excluded
            credit(3, 10_000, ts = 2_000, status = TxnStatus.DISCARDED),   // excluded
        )
        // 100_000 − 40_000 = 60_000 (the discarded debit and credit vanish)
        assertEquals(60_000L, BalanceCalculator.available(anchors, txns, ownVpas, ownNames))
    }

    @Test fun `PENDING and UNCONFIRMED debits are counted tentatively`() {
        val anchors = listOf(anchor(baseline = 100_000, anchoredAt = 1_000))
        val txns = listOf(
            debit(1, 30_000, ts = 2_000, status = TxnStatus.PENDING),
            debit(2, 25_000, ts = 2_000, status = TxnStatus.UNCONFIRMED),
        )
        // Both tentative debits subtract: 100_000 − 30_000 − 25_000 = 45_000
        assertEquals(45_000L, BalanceCalculator.available(anchors, txns, ownVpas, ownNames))
    }

    @Test fun `self-transfers are excluded from the available ledger`() {
        val anchors = listOf(anchor(baseline = 100_000, anchoredAt = 1_000))
        val txns = listOf(
            debit(1, 40_000, ts = 2_000),                                  // real spend → counts
            debit(2, 60_000, ts = 2_000, payeeVpa = "bram@oksbi"),      // self-transfer → excluded
        )
        // 100_000 − 40_000 = 60_000 (the move-to-own-account doesn't reduce wealth)
        assertEquals(60_000L, BalanceCalculator.available(anchors, txns, ownVpas, ownNames))
    }

    // ── available(): the re-anchor CUTOFF (the double-counting guard) ────────────

    @Test fun `re-anchor cutoff counts only txns at or after the latest anchor, summing both baselines`() {
        // Onboarding anchor, then a fresh "Update balance" anchor at a later timestamp.
        val anchors = listOf(
            anchor(baseline = 100_000, anchoredAt = 1_000),   // old
            anchor(baseline = 200_000, anchoredAt = 5_000),   // latest → cutoff = 5_000
        )
        // baseline = Σ baselines = 300_000; cutoff = max(anchoredAt) = 5_000.
        val txns = listOf(
            debit(1, 70_000, ts = 3_000),   // BEFORE cutoff → must NOT be subtracted (history guard)
            debit(2, 20_000, ts = 5_000),   // AT cutoff (>= is inclusive) → counts
            debit(3, 30_000, ts = 6_000),   // AFTER cutoff → counts
        )
        // 300_000 − (20_000 + 30_000) = 250_000. The 70_000 pre-cutoff debit is absent.
        val result = BalanceCalculator.available(anchors, txns, ownVpas, ownNames)
        assertEquals(250_000L, result)

        // Explicit: had the pre-cutoff debit been (wrongly) subtracted, we'd see 250_000 − 70_000.
        assertFalse("pre-cutoff debit must not be double-counted", result == 250_000L - 70_000L)

        // And the baseline really is the SUM of both anchors, not just the latest.
        val baselineOnly = BalanceCalculator.available(anchors, emptyList(), ownVpas, ownNames)
        assertEquals(300_000L, baselineOnly)
    }

    // ── anchorStampFor(): the "Add account" guard (must NOT re-cut history) ───────

    @Test fun `adding an account at anchorStampFor lifts available by exactly the new baseline`() {
        // An established snapshot at T0 = 1_000 WITH real debits after it — the exact condition a naive
        // `now` stamp would break (those debits would fall out of the moved cutoff and inflate balance).
        val existing = listOf(anchor(baseline = 100_000, anchoredAt = 1_000))
        val txns = listOf(
            debit(1, 30_000, ts = 2_000),
            debit(2, 20_000, ts = 3_000),
        )
        val before = BalanceCalculator.available(existing, txns, ownVpas, ownNames)
        assertEquals(50_000L, before)   // 100_000 − 50_000

        // The safe stamp is the accounts' shared cutoff (max anchoredAt = 1_000), NOT the passed `now`.
        val stamp = BalanceCalculator.anchorStampFor(existing, now = 9_999)
        assertEquals(1_000L, stamp)

        val after = existing + anchor(baseline = 40_000, anchoredAt = stamp)
        val result = BalanceCalculator.available(after, txns, ownVpas, ownNames)

        // available rises by EXACTLY the new baseline; the post-T0 debits stay subtracted.
        assertEquals(before + 40_000L, result)   // 90_000
        // Had we stamped at `now` (9_999), the cutoff would jump past both debits → 140_000. Prove not.
        assertFalse("adding an account must not re-cut history and inflate", result == 140_000L)
    }

    @Test fun `anchorStampFor falls back to now when there are no accounts yet`() {
        assertEquals(7_777L, BalanceCalculator.anchorStampFor(emptyList(), now = 7_777))
    }

    // ── isSelfTransfer ──────────────────────────────────────────────────────────

    @Test fun `payeeVpa in ownVpas is a self-transfer`() {
        val txn = debit(1, 10_000, ts = 1_000, payeeVpa = "bram@oksbi")
        assertTrue(BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames))
    }

    @Test fun `an outside vpa is not a self-transfer`() {
        val txn = debit(1, 10_000, ts = 1_000, payeeVpa = "merchant@okaxis")
        assertFalse(BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames))
    }

    @Test fun `name containment matches in both directions`() {
        // own short handle ⊂ full payee name
        val full = debit(1, 10_000, ts = 1_000, payeeName = "Bram Stoker")
        assertTrue(BalanceCalculator.isSelfTransfer(full, ownVpas, ownNames))
        // full own name ⊃ short payee handle ("bram" payee vs a longer own name)
        val short = debit(2, 10_000, ts = 1_000, payeeName = "bram")
        assertTrue(BalanceCalculator.isSelfTransfer(short, ownVpas, setOf("bram stoker")))
    }

    @Test fun `a sub-4-character name is never a self-transfer even when it would otherwise match`() {
        // "abc" == own "abc" WOULD match name == own — the length guard must short-circuit first.
        val txn = debit(1, 10_000, ts = 1_000, payeeName = "abc")
        assertFalse(BalanceCalculator.isSelfTransfer(txn, ownVpas, setOf("abc")))
    }

    @Test fun `null name and null vpa is not a self-transfer`() {
        val txn = debit(1, 10_000, ts = 1_000)   // payeeName + payeeVpa both default null
        assertFalse(BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames))
    }

    @Test fun `an unrelated payee name is not a self-transfer`() {
        val txn = debit(1, 10_000, ts = 1_000, payeeName = "Reliance Fresh")
        assertFalse(BalanceCalculator.isSelfTransfer(txn, ownVpas, ownNames))
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private fun anchor(baseline: Long, anchoredAt: Long, id: String = "a-$anchoredAt-$baseline") =
        BalanceAnchorEntity(
            id = id,
            accountLabel = "HDFC",
            baselinePaise = baseline,
            anchoredAt = anchoredAt,
        )

    private fun debit(
        n: Int, paise: Long, ts: Long,
        status: TxnStatus = TxnStatus.CONFIRMED,
        payeeVpa: String? = null,
        payeeName: String? = null,
    ) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.DEBIT, status = status,
        payeeName = payeeName, payeeVpa = payeeVpa,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )

    private fun credit(
        n: Int, paise: Long, ts: Long,
        status: TxnStatus = TxnStatus.CONFIRMED,
    ) = TransactionEntity(
        id = "t$n", amountPaise = paise, direction = Direction.CREDIT, status = status,
        timestampEvent = ts, timestampCaptured = ts, source = Source.A11Y,
    )
}
