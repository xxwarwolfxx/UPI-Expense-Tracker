package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.parse.CaptureParser
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.util.Money

/**
 * SBI alerts. The one that matters most is the current UPI debit — "Dear UPI user A/C X1234 debited by
 * 250.0 on date 12Sep26 trf to <payee> Refno <rrn> If not u? call-…" — which has no Rs/INR before the
 * amount and so was never read at all until v2: every SBI-funded payment went without its bank backstop.
 * Also: older "your A/c X1234-debited by Rs250 … Ref No", credits, core-banking and debit-card alerts, and
 * SBI Card (debits only — see [BankSenders.isCardIssuer]). Routed by the sender header only.
 */
class SbiSmsParser : CaptureParser {
    override val name = "sms-sbi"
    override val version = 2

    override fun canParse(raw: RawCapture): Boolean =
        raw.source == Source.SMS && BankSenders.bankOf(raw.sender) == BankSenders.SBI

    override fun parse(raw: RawCapture): ParsedTxn? {
        val body = raw.text
        if (SmsPatterns.isNotATransaction(body)) return null
        val amount = SmsPatterns.amount(body)?.let { Money.parsePaise(it) }?.takeIf { it > 0 } ?: return null
        val dir = SmsPatterns.direction(body) ?: return null
        if (dir == Direction.CREDIT && BankSenders.isCardIssuer(raw.sender)) return null
        val avl = if (dir == Direction.CREDIT) {
            SmsPatterns.AVL_BAL.find(body)?.groupValues?.get(1)?.let { Money.parsePaise(it) }
        } else null
        return ParsedTxn(
            amountPaise = amount,
            direction = dir,
            payeeName = SmsPatterns.PAYEE_TO.find(body)?.groupValues?.get(1)?.trim(),
            payeeVpa = SmsPatterns.VPA.find(body)?.groupValues?.get(1),
            payerAccountLast4 = SmsPatterns.LAST4.find(body)?.groupValues?.get(1),
            bankLabel = BankSenders.SBI,
            rrn = SmsPatterns.rrn(body),
            availableBalancePaise = avl,
            parserName = name,
            parserVersion = version,
        )
    }
}
