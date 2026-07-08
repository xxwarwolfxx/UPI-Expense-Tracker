package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction

/** Structured result of parsing one RawCapture. Money in paise. */
data class ParsedTxn(
    val amountPaise: Long,
    val direction: Direction,
    val payeeName: String? = null,
    val payeeVpa: String? = null,
    val payerAccountLast4: String? = null,
    val bankLabel: String? = null,
    val rrn: String? = null,
    val availableBalancePaise: Long? = null,
    val parserName: String,
    val parserVersion: Int,
)
