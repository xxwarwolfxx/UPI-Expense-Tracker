package com.goushik.upiwallet.domain.review

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.domain.SampleData

/**
 * Recognises rows written by the developer sample-data seeder ([SampleData.seed]) so they can be taken out
 * of a real ledger. Background: on 5 Jun the seeder ran on the owner's daily install (before the debug
 * build got its own app id), and its invented payments have sat in his totals ever since. Pure +
 * unit-tested, and checked against his real export: it finds exactly the seeder's rows and no real payment.
 *
 * Matching by payee name alone is NOT safe — real payments share the fixtures' merchant names. A row
 * counts as seeded only when EVERYTHING the seeder wrote lines up:
 *  - the exact fixture payee name (the seeder writes ALL-CAPS), amount, direction and source;
 *  - the exact reference the seeder invents for its SMS fixtures (and none for the others);
 *  - and its date sits exactly where that fixture's offset puts it from ONE shared seed moment, together
 *    with at least [MIN_RUN] other fixtures. A seed run dates every row as `now − offset`, so its rows all
 *    point back to the same millisecond; a real payment would have to match a fixture AND land on that
 *    millisecond, which can't happen by chance.
 *
 * Status is ignored on purpose: a seeded row that was already removed is still a seeded row (the caller
 * picks the live ones to remove).
 */
object SampleRows {
    /** Fewest fixtures that must agree on one seed moment. */
    const val MIN_RUN = 3

    private data class Key(val payeeName: String, val amountPaise: Long, val direction: Direction)

    private val byKey: Map<Key, SampleData.Fingerprint> by lazy {
        SampleData.FINGERPRINTS.associateBy { Key(it.payeeName, it.amountPaise, it.direction) }
    }

    /** The seeder's rows in [all], any status, in [all]'s order. */
    fun find(all: List<TransactionEntity>): List<TransactionEntity> {
        // Each fixture-shaped row, keyed by the seed moment its date implies.
        val bySeedMoment = HashMap<Long, MutableList<TransactionEntity>>()
        for (t in all) {
            val name = t.payeeName ?: continue
            val fp = byKey[Key(name, t.amountPaise, t.direction)] ?: continue
            if (t.source != fp.source || t.rrn != fp.rrn) continue
            bySeedMoment.getOrPut(t.timestampEvent + fp.offsetMs) { mutableListOf() } += t
        }
        val seeded = bySeedMoment.values
            .filter { run -> run.map { it.payeeName }.distinct().size >= MIN_RUN }
            .flatten()
            .mapTo(HashSet()) { it.id }
        return all.filter { it.id in seeded }
    }

    /** The profile's UPI IDs that are the seeder's sample IDs (empty = none). Case-insensitive, like
     *  self-transfer matching. Any one of them means the profile was never fixed after the seed. */
    fun sampleIdsIn(ownVpasCsv: String?): Set<String> {
        val sample = normalize(SampleData.SAMPLE_OWN_VPAS)
        return normalize(ownVpasCsv.orEmpty()).filterTo(LinkedHashSet()) { it in sample }
    }

    private fun normalize(csv: String): Set<String> =
        csv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
