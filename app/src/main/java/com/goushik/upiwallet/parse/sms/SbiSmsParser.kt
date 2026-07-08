package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.parse.CaptureParser
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.util.Money

class SbiSmsParser : CaptureParser {
    override val name = "sms-sbi"
    override val version = 1

    override fun canParse(raw: RawCapture): Boolean =
        raw.source == Source.SMS && "${raw.sender ?: ""} ${raw.text}".contains("SBI", ignoreCase = true)

    override fun parse(raw: RawCapture): ParsedTxn? {
        val body = raw.text
        val amount = SmsPatterns.AMOUNT.find(body)?.groupValues?.get(1)?.let { Money.parsePaise(it) } ?: return null
        val dir = SmsPatterns.direction(body) ?: return null
        val avl = if (dir == Direction.CREDIT) {
            SmsPatterns.AVL_BAL.find(body)?.groupValues?.get(1)?.let { Money.parsePaise(it) }
        } else null
        return ParsedTxn(
            amountPaise = amount,
            direction = dir,
            payeeName = SmsPatterns.PAYEE_TO.find(body)?.groupValues?.get(1)?.trim(),
            payeeVpa = SmsPatterns.VPA.find(body)?.groupValues?.get(1),
            payerAccountLast4 = SmsPatterns.LAST4.find(body)?.groupValues?.get(1),
            bankLabel = "SBI",
            rrn = SmsPatterns.rrn(body),
            availableBalancePaise = avl,
            parserName = name,
            parserVersion = version,
        )
    }
}
