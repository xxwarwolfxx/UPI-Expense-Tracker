package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.parse.CaptureParser
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.util.Money

/**
 * HDFC Bank alerts ("Sent Rs.X From HDFC Bank A/C *1234 / To <payee> / On dd/mm/yy / Ref <rrn>",
 * "Credit Alert! Rs.X credited to HDFC Bank A/c XX1234 … (UPI <rrn>)", "UPI Mandate: Sent Rs.X …").
 * Routed by the sender header only ([BankSenders]) — a body that merely mentions HDFC is not HDFC's.
 */
class HdfcSmsParser : CaptureParser {
    override val name = "sms-hdfc"
    override val version = 2

    override fun canParse(raw: RawCapture): Boolean =
        raw.source == Source.SMS && BankSenders.bankOf(raw.sender) == BankSenders.HDFC

    override fun parse(raw: RawCapture): ParsedTxn? {
        val body = raw.text
        if (SmsPatterns.isNotATransaction(body)) return null
        val amount = SmsPatterns.amount(body)?.let { Money.parsePaise(it) }?.takeIf { it > 0 } ?: return null
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
            bankLabel = BankSenders.HDFC,
            rrn = SmsPatterns.rrn(body),
            availableBalancePaise = avl,
            parserName = name,
            parserVersion = version,
        )
    }
}
