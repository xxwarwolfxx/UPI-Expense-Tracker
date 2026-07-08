package com.goushik.upiwallet.util

import kotlin.math.abs

/** Money is stored as paise (Long), never floats. Helpers for parsing captured text + display. */
object Money {
    /** Parse a rupee string like "1,234.50" or "249.0" (symbol already stripped) into paise. */
    fun parsePaise(amount: String): Long? {
        val cleaned = amount.replace(",", "").trim()
        val d = cleaned.toDoubleOrNull() ?: return null
        return Math.round(d * 100.0)
    }

    /** "-₹1,234.50" style. */
    fun format(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val a = abs(paise)
        return "%s₹%,d.%02d".format(sign, a / 100, a % 100)
    }

    /** Two-tone split for the hero balance: ("₹48,250", ".00") — the paise part renders dimmed. */
    fun formatParts(paise: Long): Pair<String, String> {
        val sign = if (paise < 0) "-" else ""
        val a = abs(paise)
        return "%s₹%,d".format(sign, a / 100) to ".%02d".format(a % 100)
    }
}
