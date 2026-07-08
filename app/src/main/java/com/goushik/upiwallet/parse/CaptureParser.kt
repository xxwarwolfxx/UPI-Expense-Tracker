package com.goushik.upiwallet.parse

/** A versioned parser. `canParse` routes (cheap); `parse` extracts (may still return null on a miss). */
interface CaptureParser {
    val name: String
    val version: Int
    fun canParse(raw: RawCapture): Boolean
    fun parse(raw: RawCapture): ParsedTxn?
}
