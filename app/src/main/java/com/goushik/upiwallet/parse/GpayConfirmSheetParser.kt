package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.util.Money

/**
 * Parses the Google Pay native UPI confirm sheet text (a11y). The qualifier is tightened per the red-team:
 * it requires confirm-sheet-specific anchors, not generic tokens — a literal `Pay ₹<amt>`, EXACTLY one
 * distinct amount (multiple ⇒ a list/history, reject), a payee, and a payer-bank line. The bank
 * alternation intentionally drops `@[a-z]+` (which made "bank present" trivially true on any VPA screen).
 *
 * NOTE: every Phase-0.5 sample was a P2P self-transfer; real merchant/QR sheets are unsampled, so a
 * WEAK verdict (single amount + payee + bank, but no literal "Pay") is captured-but-flagged rather than
 * dropped, and every verdict is logged so the qualifier can be tuned against real dumps.
 *
 * Phase 4: this is now one member of a package-keyed [ConfirmSheetParser] family (was the lone parser).
 */
class GpayConfirmSheetParser : ConfirmSheetParser {
    override val pkg = PKG
    override val name = "a11y-confirm-sheet"
    override val version = 1
    override val active = true

    override fun parse(raw: RawCapture): ParsedTxn? {
        val q = qualify(raw.text)
        if (q.verdict == QualifyVerdict.REJECTED) return null
        val amount = q.amountPaise ?: return null
        val payee = PAYEE.find(raw.text)?.groupValues?.get(1)?.trim()
        val bank = BANK.find(raw.text)?.value
        return ParsedTxn(
            amountPaise = amount,
            direction = Direction.DEBIT,      // a confirm sheet is always a spend
            payeeName = payee,
            bankLabel = bank,
            parserName = name,
            parserVersion = version,
        )
    }

    override fun qualify(text: String): QualifyResult {
        val payAmount = PAY_AMOUNT.find(text)?.groupValues?.get(1)?.let { Money.parsePaise(it) }
        val distinct = ANY_AMOUNT.findAll(text)
            .mapNotNull { Money.parsePaise(it.groupValues[1]) }
            .toSet()
        val payee = PAYEE.find(text)?.groupValues?.get(1)?.trim()
        val bank = BANK.find(text)?.value

        if (distinct.isEmpty()) return QualifyResult(QualifyVerdict.REJECTED, "no amount")
        if (distinct.size > 1) {
            return QualifyResult(QualifyVerdict.REJECTED, "multiple amounts (${distinct.size}) — list, not a sheet")
        }
        val amount = payAmount ?: distinct.first()
        val missing = buildList {
            if (payee == null) add("payee")
            if (bank == null) add("bank")
        }
        return when {
            payAmount != null && payee != null && bank != null ->
                QualifyResult(QualifyVerdict.QUALIFIED, "Pay-anchored amount + payee + bank", amount)
            payee != null && bank != null ->
                QualifyResult(QualifyVerdict.WEAK, "single amount + payee + bank, no literal 'Pay'", amount)
            else ->
                QualifyResult(QualifyVerdict.REJECTED, "missing ${missing.joinToString("+")}")
        }
    }

    companion object {
        const val PKG = "com.google.android.apps.nbu.paisa.user"

        // Anchored to the literal action token — NOT a bare ₹.
        private val PAY_AMOUNT = Regex("(?i)\\bPay\\s*(?:₹|Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        private val ANY_AMOUNT = Regex("(?:₹|Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        private val PAYEE = Regex("(?i)\\bTo\\s+([A-Z0-9][A-Za-z0-9 ._@-]{1,40})")
        // `@[a-z]+` dropped on purpose (red-team): it made "bank present" trivially true.
        private val BANK = Regex(
            "(?i)\\b(HDFC|State Bank of India|SBI|ICICI|Axis|Kotak|Punjab National|PNB|" +
                "Bank of Baroda|Canara|Yes Bank|IDFC|IndusInd|Union Bank)\\b"
        )
    }
}
