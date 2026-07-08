package com.goushik.upiwallet.parse

import android.util.Log
import com.goushik.upiwallet.parse.sms.HdfcSmsParser
import com.goushik.upiwallet.parse.sms.SbiSmsParser

/**
 * Versioned, ordered registry. Routes a RawCapture to the parsers that claim it; first successful parse
 * wins. Every miss is logged (Logcat now; a parse_misses table later) so coverage gaps surface.
 *
 * a11y confirm-sheet capture is driven directly by the capture service (it needs the qualifier), so this
 * registry holds the bank-SMS parsers.
 */
class ParserRegistry(private val parsers: List<CaptureParser>) {

    fun parse(raw: RawCapture): ParsedTxn? {
        val matched = parsers.filter { it.canParse(raw) }
        for (p in matched) {
            p.parse(raw)?.let { return it }
        }
        Log.w(
            TAG,
            "PARSE MISS source=${raw.source} sender=${raw.sender} matched=${matched.map { it.name }} " +
                "text=\"${raw.text.replace("\n", " ").take(160)}\"",
        )
        return null
    }

    companion object {
        const val TAG = "UpiWallet"
        fun default(): ParserRegistry = ParserRegistry(listOf(HdfcSmsParser(), SbiSmsParser()))
    }
}
