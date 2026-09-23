package com.goushik.upiwallet.parse

/**
 * Shared shape test for every a11y snapshot: **is this screen ASKING for money, or merely DESCRIBING
 * payments that already happened?**
 *
 * A chat, an activity list, a receipt and an Autopay mandate page all *describe* payments, and their text
 * is close enough to a live confirm sheet that a keyword list can't tell them apart. What separates them is
 * shape — a screen that describes payments carries *several* of them, so it shows repeated clock times,
 * repeated dates, more than one outcome word, or list-only chrome. A live pay sheet carries exactly one
 * payment and none of that.
 *
 * Every signal below is deliberately near-impossible on a live pay sheet, and was checked against the 459
 * real Google Pay screens stored in the user's own `raw_events` (2026-08-22 replay): **zero** of them trips
 * any of these. That check matters more than it sounds — the first pass at this list used the bare token
 * `"message"`, which flagged five genuine pay sheets because Google Pay's own sheet says *"Add a message
 * (optional)"*. Tokens here come from device dumps and are corpus-verified, never from imagination.
 *
 * Pure and stateless so it unit-tests on the JVM (see `ScreenShapeTest`, `CaptureCorpusTest`).
 */
object ScreenShape {

    /** "3:45 pm" / "17:30" — a chat/list stamps every row; a confirm sheet shows no clock at all.
     *  The am/pm suffix is optional so a device set to 24-hour time keeps this defense. */
    private val CLOCK = Regex("\\b\\d{1,2}:\\d{2}(?:\\s?(?:am|pm|AM|PM))?\\b")

    /** "11 Aug" — same argument as [CLOCK], for day-grouped lists. */
    private val DATE_TOKEN = Regex(
        "(?i)\\b\\d{1,2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b",
    )

    /** Outcome words. One can appear on a success screen; TWO DISTINCT ones means a list of outcomes. */
    private val OUTCOME = Regex("(?i)\\b(paid|received|sent|failed|refunded|completed)\\b")

    /** Chrome that only a history/detail/chat surface has. */
    private val HISTORY_CHROME = Regex(
        "(?i)(UPI transaction ID|Pay again|Type a message|transaction history|Split expense|Show transaction)",
    )

    /**
     * Returns a short human reason when [text] is describing payments rather than asking for one, or null
     * when the screen is shaped like a live sheet. The reason is logged, so a future misfire is diagnosable
     * from logcat instead of guesswork.
     */
    fun describesPayments(text: String): String? {
        // DISTINCT values, not occurrences: the service reads both a node's text and its
        // contentDescription, which routinely duplicate — one visible timestamp must not count as two.
        val clocks = CLOCK.findAll(text).map { it.value.lowercase().replace(" ", "") }.toSet().size
        if (clocks >= 2) return "$clocks clock times"
        val dates = DATE_TOKEN.findAll(text).map { it.value.lowercase() }.toSet().size
        if (dates >= 2) return "$dates date markers"
        val outcomes = OUTCOME.findAll(text).map { it.value.lowercase() }.toSet()
        if (outcomes.size >= 2) return "outcome words ${outcomes.sorted()}"
        HISTORY_CHROME.find(text)?.let { return "history chrome \"${it.value}\"" }
        return null
    }
}
