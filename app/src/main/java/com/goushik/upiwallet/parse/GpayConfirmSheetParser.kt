package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.util.Money

/**
 * Parses the Google Pay native UPI confirm sheet text (a11y).
 *
 * **The rule this hangs on: the screen must be ASKING for money.** Recording is gated on ONE *action
 * anchor*: the literal `Pay ₹<amt>` button, with the amount read from it. (The UPI-PIN screen used to be a
 * second anchor; it was removed because every PIN screen without a `Pay ₹` was an autopay mandate — see
 * below.) A screen that merely shows an amount, a "To <name>" and a bank name is not enough: a chat, a
 * receipt, an old transaction's detail page and an Autopay mandate page all satisfy that, which is exactly
 * how the owner's ledger picked up ₹19,509 of payments he never made.
 *
 * **Measured, not guessed** (2026-08-22, replayed over the 459 Google Pay screens the owner's own app had
 * stored in `raw_events`):
 *  - 394 of the live captures carry the literal `Pay ₹` anchor — every genuine payment does;
 *  - the only 5 screens that showed the PIN pad WITHOUT a `Pay ₹` were all AUTOPAY MANDATE SETUPS
 *    ("Setting an AUTOPAY of ₹X"), where the ₹ figure is the mandate's LIMIT, not a charge. Two of them
 *    booked money that never moved (a ₹14,999 credit-line cap; a ₹5,000 GeForce cap for a smaller
 *    subscription); the three that DID move money (Google Play) were each independently recorded by the
 *    bank's own debit SMS. So the PIN screen is deliberately NOT an anchor: a mandate approval asks for
 *    your PIN but is a permission for the future, and the SMS path records whatever actually debits;
 *  - 19 carry no action anchor at all — and **all 19 are phantoms**, worth ₹19,509.06. Most are the same
 *    screen shape: he pays, then opens the app minutes later to check, and the receipt gets read as a
 *    second payment.
 *  - the rule loses **zero** of the 150 captures a bank SMS independently proved real.
 *
 * This parser therefore emits no WEAK verdict at all any more: either the screen carries the literal
 * `Pay ₹<amt>` button (QUALIFIED, amount read FROM it) or it records nothing. A future Google rewording of
 * the mandate screen slips past any literal token list — but not past the structural rule, because a
 * reworded mandate screen still won't carry a `Pay ₹` button. A hypothetical genuine payment flow with a
 * PIN pad and no `Pay ₹` (none exists in the 459 sampled screens) is caught by the bank SMS ~90s later.
 *
 * **Any bank, not a list of banks.** The payer bank used to have to be one of the names in
 * [ConfirmSheetPatterns.BANK], so a Federal Bank or Indian Bank user got NO Google Pay capture at all, with
 * nothing to say so (and no SMS backstop — only HDFC and SBI texts are read). But the screen that carries
 * the `Pay ₹` button is the NPCI UPI-PIN screen, which captions the payer's bank line with the fixed labels
 * "Bank Name" / "Masked Account Number" whatever the bank is — all 420 stored `Pay ₹` screens carry them.
 * Those captions are now the bank signal, and the line above "Bank Name" is the label when the bank is not
 * a listed one. A listed name still wins, so existing rows keep their familiar "HDFC" / "SBI" labels.
 *
 * A screen that REPORTS an outcome ("Payment successful", "Paid to …") is rejected even if a `Pay ₹` node
 * lingers underneath it — see [ConfirmSheetPatterns.outcomeHeadline].
 *
 * Phase 4: this is one member of a package-keyed [ConfirmSheetParser] family (was the lone parser).
 */
class GpayConfirmSheetParser : ConfirmSheetParser {
    override val pkg = PKG
    override val name = "a11y-confirm-sheet"
    override val version = 4      // v4 = any payer bank (NPCI captions) + outcome-headline reject
    override val active = true

    override fun parse(raw: RawCapture): ParsedTxn? {
        val q = qualify(raw.text)
        if (q.verdict == QualifyVerdict.REJECTED) return null
        val amount = q.amountPaise ?: return null
        val payee = PAYEE.find(raw.text)?.groupValues?.get(1)?.trim()
        return ParsedTxn(
            amountPaise = amount,
            direction = Direction.DEBIT,      // a confirm sheet is always a spend
            payeeName = payee,
            bankLabel = payerBank(raw.text),
            parserName = name,
            parserVersion = version,
        )
    }

    override fun qualify(text: String): QualifyResult {
        // 1. A screen that DESCRIBES payments can never record one, however convincing its text.
        ScreenShape.describesPayments(text)?.let {
            return QualifyResult(QualifyVerdict.REJECTED, "describes payments ($it)")
        }

        // 2. A screen reporting how a payment ENDED is not asking for one, whatever sits underneath it.
        ConfirmSheetPatterns.outcomeHeadline(text)?.let {
            return QualifyResult(QualifyVerdict.REJECTED, "reports an outcome (\"$it\")")
        }

        // 3. An autopay/mandate setup asks for your PIN, but it is a PERMISSION, not a payment — and the
        // ₹ figure on it is the mandate's LIMIT ("up to"), not what gets charged. Booking it once turned a
        // ₹400/month subscription into a ₹15,000 spend. Whatever actually debits arrives with its own SMS.
        if (MANDATE_SETUP.containsMatchIn(text)) {
            return QualifyResult(QualifyVerdict.REJECTED, "autopay mandate setup — a permission, not a payment")
        }

        // 4. It must be ASKING: the literal Pay button, with the amount read FROM it.
        val payAmount = PAY_AMOUNT.find(text)?.groupValues?.get(1)?.let { Money.parsePaise(it) }
            ?: return QualifyResult(QualifyVerdict.REJECTED, "no 'Pay ₹' anchor — the screen never asks to pay")
        if (payAmount <= 0) return QualifyResult(QualifyVerdict.REJECTED, "non-positive amount")

        val payee = PAYEE.find(text)?.groupValues?.get(1)?.trim()
        // Any payer bank: a listed name OR the NPCI PIN screen's own bank captions (see the class KDoc).
        val hasBank = BANK.containsMatchIn(text) || NPCI_BANK_CAPTION.containsMatchIn(text)
        val missing = buildList {
            if (payee == null) add("payee")
            if (!hasBank) add("bank")
        }
        if (missing.isNotEmpty()) return QualifyResult(QualifyVerdict.REJECTED, "missing ${missing.joinToString("+")}")

        // Amount comes FROM the anchor, so a second amount on screen (a wallet balance, a cart total) is
        // harmless — the same reasoning PhonePe and Paytm already use. Dropping the old blanket "more than
        // one amount ⇒ reject" is what finally lets an unsampled merchant sheet through.
        return QualifyResult(QualifyVerdict.QUALIFIED, "Pay-anchored amount + payee + bank", payAmount)
    }

    /** The payer bank's label: a listed name first (keeps "HDFC"/"SBI" stable for existing rows and the
     *  account filters), else whatever the PIN screen prints above its "Bank Name" caption. */
    private fun payerBank(text: String): String? =
        BANK.find(text)?.value
            ?: NPCI_BANK_LINE.find(text)?.groupValues?.get(1)?.trim()
                ?.takeIf { line -> line.any(Char::isLetter) && !line.equals("Logo", ignoreCase = true) }

    companion object {
        const val PKG = "com.google.android.apps.nbu.paisa.user"

        // Anchored to the literal action token — NOT a bare ₹. Whitespace is SAME-LINE only ([ \t]
        // plus the no-break spaces the apps emit): collectText joins every node with a newline, so a
        // \s here would let a bare bottom-bar "Pay" node anchor onto whatever amount node happens to
        // follow it in the tree — two unrelated elements forming a fake action anchor. The real button
        // renders as one node ("Pay ₹87.00"); the corpus replay confirms that for all 394 anchored
        // captures.
        private val PAY_AMOUNT =
            Regex("(?i)\\bPay[ \\t\\u00A0\\u202F]*(?:₹|Rs\\.?|INR)[ \\t\\u00A0\\u202F]?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        /** The mandate-approval PIN screen's own headline, verbatim from every sampled setup screen. An
         *  explicit reject with its own log reason — though even without it, these screens carry no
         *  `Pay ₹` button and would fall to the anchor rule. */
        private val MANDATE_SETUP = Regex("(?i)Setting an AUTOPAY")

        // The word "To" is case-SENSITIVE; the payee itself is NOT. Both halves are load-bearing and
        // both were proven on the stored corpus:
        //  - a case-folded "to" matches the PIN screen's own warning line ("…UPI PIN to receive money",
        //    present on every in-flight screen), minting the fake payee "receive money" — which would
        //    make the WEAK path's payee requirement vacuous and could seed the learned-rules table with
        //    a key that mis-files every future payee-less capture (CRED hit the same trap);
        //  - but CRED's stricter [A-Z0-9] first-char guard is WRONG here: GPay renders P2P recipients
        //    in lowercase ("To ramesh", "To arun", "To name@okhdfcbank") — the corpus replay showed 8
        //    real payments (₹41,630) that an uppercase-only guard would silently drop.
        private val PAYEE = Regex("\\bTo\\s+([A-Za-z0-9][A-Za-z0-9 ._@-]{1,40})")
        // `@[a-z]+` dropped on purpose (red-team): it made "bank present" trivially true.
        private val BANK = ConfirmSheetPatterns.BANK

        /** The NPCI UPI-PIN screen's fixed captions under the payer's bank line — the same whatever the
         *  bank. Whole lines only, so a payee or chat line that merely mentions a bank can't fake them. */
        private val NPCI_BANK_CAPTION = Regex("(?m)^(?:Bank Name|Masked Account Number)$")

        /** The payer-bank line itself: the whole line directly above "Bank Name" (e.g. "Federal Bank"). */
        private val NPCI_BANK_LINE = Regex("(?m)^(.{2,40})\\nBank Name$")
    }
}
