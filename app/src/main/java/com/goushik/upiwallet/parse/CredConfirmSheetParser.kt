package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.util.Money

/**
 * CRED (com.dreamplug.androidapp) UPI confirm-sheet parser. Written from a device harvest (2026-06-22, ₹1
 * self-pay): CRED's confirm/PIN screen is the closest of the three new apps to GPay's format — it shows
 * `"Pay ₹1.00"`, `"To Ramesh Kumar"`, and `"State Bank Of India - 1234"`. (CRED was expected to maybe
 * expose no P2P UPI sheet at all; it does.) CRED's a11y nodes report numeric R.id ints, not names, so — like
 * every parser — this keys purely on the flattened text.
 *
 * Anchored on the literal **"Pay ₹<amt>"** action token; amount taken FROM it; payee from "To <name>";
 * payer bank + account tail best-effort. CRED's harvest captured only the PIN screen (no success screen), so
 * its rows stay PENDING until the bank SMS confirms or the ReconcileWorker ages them — the same honest
 * a11y-only limit GPay already has.
 */
class CredConfirmSheetParser : ConfirmSheetParser {
    override val pkg = "com.dreamplug.androidapp"
    override val name = "a11y-cred"
    override val version = 1
    override val active = true

    override fun qualify(text: String): QualifyResult {
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
            payeeName = PAYEE.find(raw.text)?.groupValues?.get(1)?.trim(),
            payerAccountLast4 = LAST4.find(raw.text)?.groupValues?.get(1),
            bankLabel = ConfirmSheetPatterns.BANK.find(raw.text)?.value,
            parserName = name,
            parserVersion = version,
        )
    }

    companion object {
        private val PAY = Regex("(?i)\\bPay\\s*₹\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        // "To Ramesh Kumar". Case-SENSITIVE on purpose: a literal capital "To " + a capitalised name,
        // so the lowercase "to receive money" warning line never gets mistaken for the payee. (Kotlin's
        // (?i) would also case-fold the [A-Z0-9] guard, defeating it — so no (?i) here.)
        private val PAYEE = Regex("\\bTo\\s+([A-Z0-9][A-Za-z0-9 ._@-]{1,40})")
        // "State Bank Of India - 1234" (or "HDFC Bank - 5678").
        private val LAST4 = Regex("(?i)\\b(?:Bank|India)\\s*-\\s*([0-9]{4})\\b")
    }
}
