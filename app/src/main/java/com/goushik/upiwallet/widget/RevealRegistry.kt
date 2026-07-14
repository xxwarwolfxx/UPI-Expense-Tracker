package com.goushik.upiwallet.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-widget eye state for the home-screen widgets — PERSISTED so the widget remembers exactly what the
 * user last chose, surviving lock/unlock, process death, and reboot (a fresh process no longer resets it).
 *  - Balance reveal (large widget): default MASKED — an id present means "balance is shown".
 *  - Spend hide (small/big): default SHOWN — an id present means "spend numbers are hidden".
 *
 * Reads stay in-memory (fast); every toggle writes through to SharedPreferences. [init] loads the saved
 * ids on process start. (There is deliberately NO auto-reset on screen-off — that used to overwrite the
 * user's choice; if a balance is left revealed it stays revealed until the user taps to hide it.)
 */
object RevealRegistry {
    private const val PREFS = "widget_eye_state"
    private const val KEY_REVEALED = "revealed"
    private const val KEY_SPEND_HIDDEN = "spend_hidden"

    private var prefs: SharedPreferences? = null
    // Balance reveal: default MASKED (privacy) — an id here means "balance is shown".
    private val revealed = HashSet<Int>()
    // Spend hide: default SHOWN — an id here means "spend numbers are hidden" (opposite polarity).
    private val spendHidden = HashSet<Int>()

    /** Load persisted eye state into memory. Call once from Application.onCreate, before any widget renders. */
    @Synchronized
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        revealed.clear(); spendHidden.clear()
        loadInto(p, KEY_REVEALED, revealed)
        loadInto(p, KEY_SPEND_HIDDEN, spendHidden)
    }

    @Synchronized
    fun isRevealed(appWidgetId: Int): Boolean = revealed.contains(appWidgetId)

    @Synchronized
    fun toggle(appWidgetId: Int) {
        if (!revealed.add(appWidgetId)) revealed.remove(appWidgetId)
        save(KEY_REVEALED, revealed)
    }

    @Synchronized
    fun isSpendHidden(appWidgetId: Int): Boolean = spendHidden.contains(appWidgetId)

    @Synchronized
    fun toggleSpendHidden(appWidgetId: Int) {
        if (!spendHidden.add(appWidgetId)) spendHidden.remove(appWidgetId)
        save(KEY_SPEND_HIDDEN, spendHidden)
    }

    /** Drop saved state for removed widgets (called from onDeleted) so prefs don't leak stale ids. */
    @Synchronized
    fun forget(appWidgetIds: IntArray) {
        var changed = false
        for (id in appWidgetIds) {
            if (revealed.remove(id)) changed = true
            if (spendHidden.remove(id)) changed = true
        }
        if (changed) {
            save(KEY_REVEALED, revealed)
            save(KEY_SPEND_HIDDEN, spendHidden)
        }
    }

    private fun loadInto(p: SharedPreferences, key: String, into: HashSet<Int>) {
        p.getStringSet(key, emptySet())?.forEach { s -> s.toIntOrNull()?.let(into::add) }
    }

    private fun save(key: String, ids: Set<Int>) {
        prefs?.edit()?.putStringSet(key, ids.mapTo(HashSet()) { it.toString() })?.apply()
    }
}
