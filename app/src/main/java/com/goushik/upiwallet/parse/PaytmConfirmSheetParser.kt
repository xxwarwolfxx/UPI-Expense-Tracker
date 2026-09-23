package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.util.Money

/**
 * Paytm (net.one97.paytm) UPI confirm-sheet parser. Written from a device harvest (2026-06-22, ₹1 self-pay):
 * the instrument sheet shows `instrument-sheet-header = "Pay ₹1 from"`, the CTA `"Pay Securely ₹1"`,
 * `tv_title = "Pay ₹1.00"`, and `instrument-title = "HDFC Bank - 1234"`. The payee name only appears on the
 * later success screen (`receiver_name = "Paid to …"`), not on the confirm sheet that qualifies, so payee is
 * null at capture (the bank SMS fills it on merge).
 *
 * Anchored on the literal **"Pay … ₹<amt>"** action token; the amount is taken FROM it. This is essential
 * for Paytm specifically: its screens are littered with promo amounts ("Travel Pass @ ₹1099", "₹21 daily
 * SIP", "Refer & Win upto ₹200"), so GPay's old "exactly one distinct amount" rule would reject every real
 * payment. The Pay-token excludes those (and history/list screens).
 *
 * The success screen ("Paid to …") is an outcome headline, so [qualify] rejects it even if a "Pay ₹" node
 * lingers in the tree ([ConfirmSheetPatterns.outcomeHeadline]); the capture service resolves the open
 * episode from it via [com.goushik.upiwallet.capture.TerminalDecision], checked BEFORE qualifying.
 */
class PaytmConfirmSheetParser : ConfirmSheetParser {
    override val pkg = "net.one97.paytm"
    override val name = "a11y-paytm"
    override val version = 3      // v3 = outcome-headline reject
    override val active = true

    override fun qualify(text: String): QualifyResult {
        // A screen that DESCRIBES payments can never record one — shared with every other parser.
        ScreenShape.describesPayments(text)?.let {
            return QualifyResult(QualifyVerdict.REJECTED, "describes payments ($it)")
        }
        // ...nor can one that REPORTS how a payment ended, whatever sheet lingers under it.
        ConfirmSheetPatterns.outcomeHeadline(text)?.let {
            return QualifyResult(QualifyVerdict.REJECTED, "reports an outcome (\"$it\")")
        }
        val amount = PAY.find(text)?.groupValues?.get(1)?.let { Money.parsePaise(it) }
            ?: return QualifyResult(QualifyVerdict.REJECTED, "no 'Pay ₹' action token")
        val bank = ConfirmSheetPatterns.BANK.find(text)?.value
        return QualifyResult(QualifyVerdict.QUALIFIED, "Pay-anchored amount${bank?.let { " + $it" } ?: ""}", amount)
    }

    override fun parse(raw: RawCapture): ParsedTxn? {
        val amount = qualify(raw.text).amountPaise ?: return null
        return ParsedTxn(
            amountPaise = amount,
            direction = Direction.DEBIT,            // a confirm sheet is always a spend
            payeeName = null,                       // recipient name is on the success screen, not this sheet
            payerAccountLast4 = LAST4.find(raw.text)?.groupValues?.get(1),
            bankLabel = ConfirmSheetPatterns.BANK.find(raw.text)?.value,
            parserName = name,
            parserVersion = version,
        )
    }

    companion object {
        // "Pay ₹1 from", "Pay ₹1.00", and the CTA "Pay Securely ₹1" — allow the optional "Securely".
        private val PAY = Regex("(?i)\\bPay\\s+(?:Securely\\s+)?₹\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        // "HDFC Bank - 1234" / "SBI Bank - 5678".
        private val LAST4 = Regex("(?i)\\bBank\\s*-\\s*([0-9]{4})\\b")
    }
}
