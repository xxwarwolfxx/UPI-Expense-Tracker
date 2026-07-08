package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus

object BalanceCalculator {

    fun isSelfTransfer(txn: TransactionEntity, ownVpas: Set<String>, ownNames: Set<String>): Boolean {
        val vpa = txn.payeeVpa?.lowercase()?.trim()
        if (vpa != null && vpa in ownVpas) return true
        val name = txn.payeeName?.lowercase()?.trim() ?: return false
        if (name.length < 4) return false
        // The confirm sheet often shows a short handle / first name ("bram"), not the full legal
        // name — so match if either contains the other (e.g. "bram" ⊂ "bram stoker").
        return ownNames.any { own -> name == own || own.contains(name) || (own.length >= 4 && name.contains(own)) }
    }

    /**
     * available = Σ(anchor baselines) − Σ(debits) + Σ(credits), over rows that are not DISCARDED and
     * not self-transfers. Σ across accounts — per-account attribution is deferred. PENDING/UNCONFIRMED
     * debits are counted (tentative) to keep the glance number live; known cancels never count.
     *
     * Re-anchor semantics: only txns AT/AFTER the most-recent anchor count. At onboarding every anchor
     * shares one timestamp, so all real spends count (the verified Phase-3 behavior is unchanged). The
     * "Update balance" flow re-writes the anchors with a fresh timestamp, moving the cutoff to now — so
     * the entered balance becomes the new truth and only spends from there forward adjust it. Without
     * this, a second anchor would double-count the baseline and keep subtracting all history.
     */
    fun available(
        anchors: List<BalanceAnchorEntity>,
        txns: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
    ): Long {
        val baseline = anchors.sumOf { it.baselinePaise }
        val cutoff = anchors.maxOfOrNull { it.anchoredAt } ?: Long.MIN_VALUE
        val counted = txns.filter {
            it.status != TxnStatus.DISCARDED &&
                it.timestampEvent >= cutoff &&
                !isSelfTransfer(it, ownVpas, ownNames)
        }
        val debits = counted.filter { it.direction == Direction.DEBIT }.sumOf { it.amountPaise }
        val credits = counted.filter { it.direction == Direction.CREDIT }.sumOf { it.amountPaise }
        return baseline - debits + credits
    }

    /**
     * The timestamp to stamp a NEWLY-ADDED account with (the "Add account" flow — distinct from the
     * full "Update balance" re-anchor). A new account joins the existing snapshot at the accounts'
     * shared cutoff (`max(anchoredAt)`), so [available] sums the new baseline in but the global cutoff
     * NEVER moves forward — adding an account can only ADD its entered balance, never re-cut history and
     * inflate the existing accounts (the "totals must reconcile" guard). With no accounts yet, a fresh
     * [now] starts the first snapshot.
     */
    fun anchorStampFor(existing: List<BalanceAnchorEntity>, now: Long): Long =
        existing.maxOfOrNull { it.anchoredAt } ?: now
}
