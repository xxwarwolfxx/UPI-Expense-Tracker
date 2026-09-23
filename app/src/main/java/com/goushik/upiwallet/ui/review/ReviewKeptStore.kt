package com.goushik.upiwallet.ui.review

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The payments the owner answered "Keep" for in Review's "Did this go through?" cards, so the question
 * isn't asked again. A preference, not a column: Keep changes nothing about the payment itself (it was
 * already counted), and the rule is no schema change in this release. Fronted by a [StateFlow] so the
 * card drops the instant Keep is tapped.
 *
 * Honest limit: it lives on this phone only — a backup restored onto a new phone asks again.
 */
class ReviewKeptStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val keptState = MutableStateFlow(read())
    val keptIds: StateFlow<Set<String>> = keptState.asStateFlow()

    fun keep(txnId: String) {
        val next = keptState.value + txnId
        prefs.edit().putStringSet(KEY_KEPT, next).apply()
        keptState.value = next
    }

    // A copy: the set SharedPreferences returns must never be modified.
    private fun read(): Set<String> = prefs.getStringSet(KEY_KEPT, null)?.toSet() ?: emptySet()

    private companion object {
        const val PREFS = "review_prefs"
        const val KEY_KEPT = "backed_out_kept"
    }
}
