package com.goushik.upiwallet.parse

enum class QualifyVerdict { QUALIFIED, WEAK, REJECTED }

/** Why a snapshot did/didn't qualify as the UPI confirm sheet — drives the heavy QUALIFIED/REJECTED log. */
data class QualifyResult(
    val verdict: QualifyVerdict,
    val reason: String,
    val amountPaise: Long? = null,
)

/**
 * One UPI app's native confirm-sheet reader (a11y text). Phase 4 generalises the single GPay parser into a
 * package-keyed family: the capture service looks up the parser for the foreground app's package, [qualify]
 * gates an episode, and [parse] extracts the spend. GPay, PhonePe, Paytm and CRED are all sampled + live.
 *
 * [active] = a real, sampled parser (all four current parsers). `false` would mark a stub placeholder for a
 * not-yet-harvested app — it always REJECTs (no episode ever opens from it) but is still whitelisted so
 * learning mode can harvest its confirm-sheet node trees, until its parser is written from real device
 * dumps. We deliberately do NOT write per-app regex blind.
 */
interface ConfirmSheetParser {
    /** The UPI app package this reads (must appear in res/xml/a11y_config.xml packageNames). */
    val pkg: String
    val name: String
    val version: Int
    val active: Boolean
    fun qualify(text: String): QualifyResult
    fun parse(raw: RawCapture): ParsedTxn?
}
