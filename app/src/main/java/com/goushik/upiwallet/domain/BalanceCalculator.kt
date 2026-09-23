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
        val payee = words(name)
        return ownNames.any { own -> isOwnName(payee, own.lowercase().trim()) }
    }

    /**
     * Word-by-word match of a payee name against ONE own name. The confirm sheet often shows a short handle
     * or first name ("bram"), and a bank may shorten the surname to an initial ("bram s"), so the names are
     * lined up word by word, and the FIRST word must always be the same. Then either:
     *  - the payee's words are the START of the own name ("bram" for own "bram stoker"), where a later word
     *    may also be a single-letter initial of the other side's word, both ways: own "bram stoker" matches
     *    payee "bram s", and own "bram s" matches payee "bram stoker"; or
     *  - the payee's words START with the whole own name, word for word ("bram s" for own "bram"). Initials
     *    are not stretched this way, so "bram s traders" is not own "bram stoker".
     * Words, never raw characters: substring matching made "tarun" a self-transfer for own "arun" and
     * "kumar" one for own "sai kumar", silently dropping real payments out of every spend total.
     */
    private fun isOwnName(payee: List<String>, own: String): Boolean {
        val ownWords = words(own)
        if (ownWords.isEmpty() || payee.isEmpty()) return false
        if (payee[0] != ownWords[0]) return false
        if (payee.size <= ownWords.size) {
            return (1 until payee.size).all { sameWord(payee[it], ownWords[it]) }
        }
        // A tiny own name ("al") would claim every "al …" payee, so the widening direction needs 4+ chars.
        return own.length >= 4 && (1 until ownWords.size).all { payee[it] == ownWords[it] }
    }

    /** The same word, or one side is a single-letter initial of the other ("s" and "stoker"). */
    private fun sameWord(a: String, b: String): Boolean =
        a == b || (a.length == 1 && b.startsWith(a)) || (b.length == 1 && a.startsWith(b))

    private val WHITESPACE = Regex("\\s+")
    private fun words(s: String): List<String> = s.split(WHITESPACE).filter { it.isNotEmpty() }

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

    /**
     * The anchor set after deleting the account [id] — the inverse "totals must reconcile" guard to
     * [anchorStampFor]. The cutoff in [available] is the GLOBAL `max(anchoredAt)`, so naively deleting
     * the anchor that holds it would slide the cutoff backward and pull already-reconciled history back
     * into the sum for every remaining account. Fix: if the victim held the unique max stamp, re-stamp
     * the latest remaining anchor to the old cutoff, so the window never moves and
     * `available(after) == available(before) − victim.baselinePaise`, exactly.
     * Returns null when [id] isn't in [existing] (nothing to delete).
     */
    fun anchorsAfterDelete(existing: List<BalanceAnchorEntity>, id: String): List<BalanceAnchorEntity>? {
        if (existing.none { it.id == id }) return null
        val remaining = existing.filter { it.id != id }
        if (remaining.isEmpty()) return remaining
        val oldCutoff = existing.maxOf { it.anchoredAt }
        val latest = remaining.maxByOrNull { it.anchoredAt }!!
        if (latest.anchoredAt == oldCutoff) return remaining
        return remaining.map { if (it.id == latest.id) it.copy(anchoredAt = oldCutoff) else it }
    }
}
