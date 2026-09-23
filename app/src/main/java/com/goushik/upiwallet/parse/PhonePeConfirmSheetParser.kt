package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.util.Money

/**
 * PhonePe (com.phonepe.app) UPI confirm-sheet parser. Written from a device harvest (2026-06-22, ₹1
 * self-pay): the instrument bottom-sheet exposes `tv_action = "Pay ₹1"`, `tvInstrumentName =
 * "State Bank of India"`, and a "•• 1234" masked account tail; the recipient (`tv_payee_title`) is a masked
 * phone number for a self-pay, so payee is left null here.
 *
 * Anchored on the literal **"Pay ₹<amt>"** action token (NOT a bare ₹) and the amount is taken FROM it —
 * PhonePe's home/history shows many "₹500 - Sent Securely" rows but no singular pay action, so those screens
 * never qualify. (This replaces GPay's old "exactly one distinct amount" rule, which would misfire on a real
 * payment whose sheet also shows an account/wallet balance.)
 *
 * **The success overlay.** PhonePe paints "Payment Successful" ON TOP of this sheet, and the accessibility
 * tree still holds the sheet underneath — "Pay ₹1" included. Read naively, the success screen is a second
 * payment: two stored captures in the owner's ledger are exactly that. So [qualify] rejects any screen that
 * carries an outcome headline ([ConfirmSheetPatterns.outcomeHeadline]), and the capture service checks an
 * open episode's terminal decision ([com.goushik.upiwallet.capture.TerminalDecision]) BEFORE qualifying,
 * which is what now confirms the payment from that very overlay.
 */
class PhonePeConfirmSheetParser : ConfirmSheetParser {
    override val pkg = "com.phonepe.app"
    override val name = "a11y-phonepe"
    override val version = 3      // v3 = outcome-headline reject (the success overlay)
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
            payeeName = null,                       // PhonePe's sheet shows a masked number, not a name
            payerAccountLast4 = LAST4.find(raw.text)?.groupValues?.get(1),
            bankLabel = ConfirmSheetPatterns.BANK.find(raw.text)?.value,
            parserName = name,
            parserVersion = version,
        )
    }

    companion object {
        private val PAY = Regex("(?i)\\bPay\\s*₹\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        // "•• 1234" — the bullet-masked account tail after the bank name.
        private val LAST4 = Regex("[\\u2022\\u00b7•·]{2,}\\s*([0-9]{4})\\b")
    }
}
