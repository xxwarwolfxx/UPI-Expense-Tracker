package com.goushik.upiwallet.widget

/**
 * In-memory reveal state for the large balance widget — deliberately NOT persisted.
 *
 * It survives a data re-push within the same process (so a freshly-captured payment re-rendering the
 * widget doesn't silently un-reveal a balance you just opened), but is GONE on process death / reboot.
 * That's the point: the balance re-masks for free whenever the process restarts, and the screen-off
 * receiver clears it explicitly. Persisting it would be a privacy leak — a revealed balance would
 * survive a reboot and the launcher would redisplay it.
 */
object RevealRegistry {
    // Balance reveal: default MASKED (privacy) — an id here means "balance is shown".
    private val revealed = HashSet<Int>()
    // Spend hide: default SHOWN — an id here means "spend numbers are hidden". Spends are less sensitive
    // than the balance, so the default is the opposite; hence a SEPARATE set rather than reusing `revealed`.
    private val spendHidden = HashSet<Int>()

    @Synchronized
    fun isRevealed(appWidgetId: Int): Boolean = revealed.contains(appWidgetId)

    @Synchronized
    fun toggle(appWidgetId: Int) {
        if (!revealed.add(appWidgetId)) revealed.remove(appWidgetId)
    }

    @Synchronized
    fun isSpendHidden(appWidgetId: Int): Boolean = spendHidden.contains(appWidgetId)

    @Synchronized
    fun toggleSpendHidden(appWidgetId: Int) {
        if (!spendHidden.add(appWidgetId)) spendHidden.remove(appWidgetId)
    }

    /** Re-mask the balance AND re-show spends (screen-off / lock) — back to the privacy defaults. Returns
     *  true if anything actually changed (so the caller only re-pushes when it must). */
    @Synchronized
    fun clearAll(): Boolean {
        if (revealed.isEmpty() && spendHidden.isEmpty()) return false
        revealed.clear()
        spendHidden.clear()
        return true
    }
}
