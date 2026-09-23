package com.goushik.upiwallet.parse

import com.goushik.upiwallet.parse.sms.BankSenders
import com.goushik.upiwallet.parse.sms.HdfcSmsParser
import com.goushik.upiwallet.parse.sms.SbiSmsParser
import com.goushik.upiwallet.util.Dbg

/**
 * Versioned, ordered registry. Routes a RawCapture to the parsers that claim it; first successful parse
 * wins. Every miss is noted in debug builds so coverage gaps surface.
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
        // Never the text, not even masked: a miss is usually an OTP, a notice or a personal message.
        // The bank and the length are enough to go looking in a corpus.
        Dbg.w {
            "PARSE MISS source=${raw.source} bank=${BankSenders.bankOf(raw.sender)} " +
                "len=${raw.text.length} matched=${matched.map { it.name }}"
        }
        return null
    }

    companion object {
        fun default(): ParserRegistry = ParserRegistry(listOf(HdfcSmsParser(), SbiSmsParser()))
    }
}
