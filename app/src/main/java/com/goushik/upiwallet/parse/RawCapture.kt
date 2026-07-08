package com.goushik.upiwallet.parse

/** Normalized input to the parser layer — an a11y text dump or an SMS body. */
data class RawCapture(
    val source: String,            // Source.A11Y | Source.SMS
    val text: String,
    val packageName: String? = null,
    val eventType: String? = null,
    val sender: String? = null,    // SMS originating address
    val capturedAt: Long,
)
