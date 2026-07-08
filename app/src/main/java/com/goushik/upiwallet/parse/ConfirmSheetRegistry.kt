package com.goushik.upiwallet.parse

/**
 * Package-keyed lookup of the per-app confirm-sheet parsers (Phase 4). The capture service reads the
 * foreground app's package off the a11y event and routes to the matching parser; an unknown package
 * (anything not in a11y_config.xml packageNames) returns null and is ignored.
 *
 * KEEP [default]'s package set IN SYNC with res/xml/a11y_config.xml `android:packageNames`.
 */
class ConfirmSheetRegistry(parsers: List<ConfirmSheetParser>) {
    private val byPkg: Map<String, ConfirmSheetParser> = parsers.associateBy { it.pkg }

    /** The parser for [pkg], or null if we don't capture that app. */
    fun forPackage(pkg: String): ConfirmSheetParser? = byPkg[pkg]

    /** Every package we may receive a11y events for (the whitelist). */
    val packages: Set<String> = byPkg.keys

    companion object {
        fun default(): ConfirmSheetRegistry = ConfirmSheetRegistry(
            listOf(
                GpayConfirmSheetParser(),
                PhonePeConfirmSheetParser(),
                PaytmConfirmSheetParser(),
                CredConfirmSheetParser(),
            ),
        )
    }
}
