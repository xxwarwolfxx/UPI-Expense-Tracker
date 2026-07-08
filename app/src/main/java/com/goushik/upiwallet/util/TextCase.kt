package com.goushik.upiwallet.util

/**
 * Display-time prettifier for captured human text (payee / merchant names) that arrives ALL-CAPS from the
 * UPI confirm sheet or bank SMS. Title-cases a single-case string — "RAMESH KUMAR" → "Ramesh Kumar",
 * "swiggy" → "Swiggy" — but LEAVES an already mixed-case string untouched, so deliberate casing like
 * "iPhone" / "McDonald's" survives. The raw payload in the DB is never changed: this is display-only,
 * applied at the view-model / render layer. Returns null for null/blank so callers keep their
 * `?: payeeVpa ?: …` fallback chains. (Bank acronym labels like "HDFC"/"SBI" are NOT routed through this.)
 */
fun prettyName(raw: String?): String? {
    val s = raw?.trim()
    if (s.isNullOrEmpty()) return null
    // Mixed-case is intentional — don't mangle it. Only normalise all-UPPER / all-lower (or no-letter) text.
    if (s.any { it.isLowerCase() } && s.any { it.isUpperCase() }) return s
    return s.split(' ').joinToString(" ") { w ->
        if (w.isEmpty()) w else w[0].uppercaseChar() + w.substring(1).lowercase()
    }
}
