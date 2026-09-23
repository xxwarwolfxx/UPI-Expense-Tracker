package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host unit tests for the a11y UPI confirm-sheet parsers ([GpayConfirmSheetParser] and the PhonePe / Paytm /
 * CRED family).
 *
 * The real input is a [RawCapture] (source + joined node texts), NOT a bare string. Google Pay qualifies a
 * screen only when it is ASKING for money: the literal `Pay ₹<amt>` button (the amount is read FROM it, so
 * other amounts on screen are harmless), a `To <payee>`, and a payer-bank signal — a listed bank name or the
 * NPCI PIN screen's own "Bank Name" / "Masked Account Number" captions. A list/history shape, an outcome
 * headline or an autopay mandate setup rejects outright. The result is always a DEBIT (a confirm sheet is a
 * spend).
 *
 * Inputs mirror the device dumps: the nodes are newline-joined so the greedy, space-allowing PAYEE
 * capture (`To <name>`) is bounded by the newline (its char class excludes '\n'). Every name, account
 * tail and amount here is made up.
 */
class ConfirmSheetParserTest {

    private val parser = GpayConfirmSheetParser()

    private fun a11y(text: String) = RawCapture(source = Source.A11Y, text = text, capturedAt = 0L)

    // ── parse(): the QUALIFIED GPay confirm sheet ─────────────────────────────

    @Test fun `gpay pay-anchored sheet parses amount, payee and DEBIT direction`() {
        // "Pay ₹450" is the literal Pay-anchor; "To Ramesh Kumar" is the payee; "HDFC Bank" the payer bank.
        val r = parser.parse(a11y("Pay ₹450\nTo Ramesh Kumar\nHDFC Bank"))
        assertNotNull("a qualified confirm sheet must parse", r)
        assertEquals(45000L, r!!.amountPaise)            // ₹450 → 45000 paise
        assertEquals("Ramesh Kumar", r.payeeName)
        assertEquals(Direction.DEBIT, r.direction)        // a confirm sheet is always a spend
        assertEquals("HDFC", r.bankLabel)
        assertEquals("a11y-confirm-sheet", r.parserName)
        assertEquals("v4 = any payer bank + outcome-headline reject; rows stay traceable", 4, r.parserVersion)
    }

    // ── Any payer bank, not a list of 14 ──────────────────────────────────────
    // The real PIN screen's shape (scrubbed): Logo, the payer's bank line, then NPCI's fixed captions.
    private fun pinScreen(bankLine: String, amount: String = "₹450.00") =
        "Logo\n$bankLine\nBank Name\nMasked Account Number\nClose\nPay $amount\nTo Ramesh Kumar\n" +
            "Enter your PIN\nNever enter your UPI PIN to receive money\n1\n2\n3\n4\n5\n6\n7\n8\n9\n0\nPay"

    @Test fun `a bank missing from the list still records — the NPCI captions are the bank signal`() {
        // Used to be REJECTED "missing bank": a Federal Bank user got no Google Pay capture at all.
        for (bank in listOf("Federal Bank", "Indian Bank", "Bank of India", "IDBI Bank", "AU Small Finance Bank")) {
            val q = parser.qualify(pinScreen(bank))
            assertEquals("$bank must qualify", QualifyVerdict.QUALIFIED, q.verdict)
            assertEquals(45000L, q.amountPaise)
            val r = parser.parse(a11y(pinScreen(bank)))!!
            assertEquals("the label is the line above 'Bank Name'", bank, r.bankLabel)
            assertEquals("Ramesh Kumar", r.payeeName)
        }
    }

    @Test fun `a listed bank keeps its familiar short label`() {
        // Existing rows and the account filters say "HDFC" / "State Bank of India"; the new rule must not
        // relabel them "HDFC Bank".
        assertEquals("HDFC", parser.parse(a11y(pinScreen("HDFC Bank")))!!.bankLabel)
        assertEquals("State Bank of India", parser.parse(a11y(pinScreen("State Bank of India")))!!.bankLabel)
    }

    @Test fun `with neither a listed bank nor the NPCI captions the screen is still rejected`() {
        val q = parser.qualify("Pay ₹450\nTo Ramesh Kumar\nFederal Bank")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
    }

    @Test fun `a caption buried inside a sentence is not the NPCI caption`() {
        val q = parser.qualify("Pay ₹450\nTo Ramesh Kumar\nUpdate your Bank Name in settings")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
    }

    // ── A screen reporting an outcome is not asking for money ─────────────────

    @Test fun `every parser rejects a screen whose headline reports an outcome`() {
        val parsers = listOf(parser, PhonePeConfirmSheetParser(), PaytmConfirmSheetParser(), CredConfirmSheetParser())
        // A pay sheet every parser accepts on its own...
        val sheet = "${pinScreen("HDFC Bank - 1234")}\nPay ₹450 from"
        for (p in parsers) assertEquals("${p.name}: the bare sheet", QualifyVerdict.QUALIFIED, p.qualify(sheet).verdict)
        // ...with an outcome headline painted on top of it.
        for (headline in listOf(
            "Payment Successful", "Payment successful!", "Payment failed", "Transaction declined",
            "Payment could not be completed", "Paid to Ramesh Kumar",
        )) {
            val overlaid = "$headline\n$sheet"
            for (p in parsers) {
                assertEquals("${p.name}: '$headline'", QualifyVerdict.REJECTED, p.qualify(overlaid).verdict)
                assertNull(p.parse(a11y(overlaid)))
            }
        }
    }

    @Test fun `an outcome word that is not a whole headline does not reject a live sheet`() {
        // A payee called "Success Traders" and a promo that merely mentions failure must not blind capture.
        val sheet = pinScreen("HDFC Bank").replace("To Ramesh Kumar", "To Success Traders") +
            "\nPayment failed? Get an instant refund"
        assertEquals(QualifyVerdict.QUALIFIED, parser.qualify(sheet).verdict)
        assertEquals(QualifyVerdict.QUALIFIED, CredConfirmSheetParser().qualify(sheet).verdict)
    }

    @Test fun `PhonePe's success overlay over its pay sheet does not qualify as a second payment`() {
        // Scrubbed shape of the two stored PhonePe captures that did exactly that: the overlay's headline and
        // timestamp on top, the whole pay sheet — "Pay ₹1" included — still underneath in the tree.
        val underneath = "Total payable\n₹1\nClose\nRecommended\nState Bank of India\n••\n1234\n₹1\n" +
            "Other bank accounts\nHDFC Bank\n••\n5678\nAdd Payment Method\nAdd Bank Account\n" +
            "Add credit line on UPI\nPay ₹1\nNavigate up\nPay\nRAMESH KUMAR\nrameshk@okhdfcbank\n₹\n1\n" +
            "Add a message (optional)\nProceed To Pay"
        val p = PhonePeConfirmSheetParser()
        assertEquals("the sheet alone is a real ask", QualifyVerdict.QUALIFIED, p.qualify(underneath).verdict)
        val overlay = "Payment Successful\n23 June 2026 at 01:47 AM\n$underneath"
        assertEquals(QualifyVerdict.REJECTED, p.qualify(overlay).verdict)
        assertNull(p.parse(a11y(overlay)))
    }

    @Test fun `decimal-and-comma amount parses to exact paise`() {
        val r = parser.parse(a11y("Pay ₹1,250.00\nTo Anand Stores\nICICI Bank"))
        assertNotNull(r)
        assertEquals(125000L, r!!.amountPaise)           // 1,250.00 → comma stripped → 125000 paise
        assertEquals("Anand Stores", r.payeeName)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals("ICICI", r.bankLabel)
    }

    @Test fun `SBI sheet with Rs-prefixed amount still emits a DEBIT`() {
        // "Rs.99" exercises the Rs\.? alternation in PAY_AMOUNT.
        val r = parser.parse(a11y("Pay Rs.99\nTo Local Kirana\nSBI"))
        assertNotNull(r)
        assertEquals(9900L, r!!.amountPaise)
        assertEquals("Local Kirana", r.payeeName)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals("SBI", r.bankLabel)
    }

    // ── parse(): the screen must be ASKING for money ──────────────────────────
    // This pair used to assert the opposite — that an amount + payee + bank with no action word was worth
    // capturing. That rule is what put ₹19,509 of payments the user never made into his ledger: a receipt, a
    // chat and an Autopay page all satisfy it. Replaying his 459 stored screens showed 19 captures had no
    // action word and ALL 19 were phantoms, while zero bank-proven payments lacked one.

    @Test fun `an amount, payee and bank with no action word is rejected — the screen never asks to pay`() {
        val r = parser.parse(a11y("₹450\nTo Ramesh Kumar\nHDFC Bank"))
        assertNull("a screen that merely shows a payment must never record one", r)
    }

    @Test fun `the PIN pad alone is NOT an anchor — every PIN-without-Pay screen sampled was a mandate`() {
        // This test used to assert the opposite (the PIN screen as a WEAK anchor, "or ₹33,207 of real
        // payments would be lost"). The user's own report overturned that: all five sampled PIN-without-Pay
        // screens were autopay setups showing the mandate LIMIT — his ₹400/month subscription was booked as
        // ₹15,000. The money that actually moves at setup always arrived with its own bank SMS.
        val sheet = "₹450\nTo Ramesh Kumar\nHDFC Bank\nEnter your PIN\n" +
            "Never enter your UPI PIN to receive money"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(sheet).verdict)
        assertNull(parser.parse(a11y(sheet)))
    }

    @Test fun `an autopay mandate setup records nothing — the ₹ on it is the limit, not a charge`() {
        // Verbatim shape of the real screen (scrubbed): "Setting an AUTOPAY of ₹12000.00" with the PIN pad,
        // a payee and a bank — everything the old rules wanted, and still not a payment.
        val sheet = "Logo\nHDFC Bank\nBank Name\nMasked Account Number\nClose\n" +
            "Setting an AUTOPAY of ₹12000.00\nTo SomeService\nEnter your PIN\n" +
            "Never enter your UPI PIN to receive money\n1\n2\n3\nPay"
        val q = parser.qualify(sheet)
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
        assertNull(parser.parse(a11y(sheet)))
    }

    // ── parse(): rejections → null ────────────────────────────────────────────

    @Test fun `a non-payment OTP message with no amount parses to null`() {
        val r = parser.parse(a11y("Your OTP for login is 482913. Do not share it with anyone."))
        assertNull("no ₹/Rs/INR amount → REJECTED → null", r)
    }

    @Test fun `several amounts and no action word is rejected`() {
        val r = parser.parse(a11y("₹450\nTo Ramesh Kumar\n₹1,200\nHDFC Bank"))
        assertNull(r)
    }

    @Test fun `a Pay-anchored sheet showing a second amount still parses, reading the anchored one`() {
        // The old blanket "more than one amount ⇒ reject" is what made this parser blind to merchant sheets
        // that also print a wallet balance or a cart total — a gap its own KDoc flagged. The amount comes
        // FROM the anchor, so a second figure is harmless; PhonePe and Paytm already work this way.
        val r = parser.parse(a11y("Cart total ₹1,200\nPay ₹450\nTo Anand Stores\nHDFC Bank"))
        assertNotNull(r)
        assertEquals(45000L, r!!.amountPaise)
    }

    @Test fun `an amount with no payee or bank is rejected`() {
        val r = parser.parse(a11y("Pay ₹450"))
        assertNull("missing payee + bank → REJECTED → null", r)
    }

    // ── ConfirmSheetRegistry: package-keyed routing (Phase 4) ─────────────────

    @Test fun `registry routes each UPI app to its parser and unknown packages to null`() {
        val reg = ConfirmSheetRegistry.default()
        for (pkg in listOf(
            GpayConfirmSheetParser.PKG, "com.phonepe.app", "net.one97.paytm", "com.dreamplug.androidapp",
        )) {
            val p = reg.forPackage(pkg)
            assertNotNull("$pkg must resolve to a parser", p)
            assertEquals("the parser must own the package it's keyed under", pkg, p!!.pkg)
            assertTrue("$pkg is sampled + live", p.active)
        }
        assertNull("a package we don't capture resolves to null", reg.forPackage("com.example.notupi"))
    }

    @Test fun `registry whitelist matches the a11y_config package set`() {
        // Guard against the two-sources-of-truth drift (registry vs res/xml/a11y_config.xml).
        assertEquals(
            setOf(
                "com.google.android.apps.nbu.paisa.user",
                "com.phonepe.app",
                "net.one97.paytm",
                "com.dreamplug.androidapp",
            ),
            ConfirmSheetRegistry.default().packages,
        )
    }

    // ── qualify(): the public verdict that parse() hides ──────────────────────

    @Test fun `qualify reports QUALIFIED with the Pay-anchored amount`() {
        val q = parser.qualify("Pay ₹450\nTo Ramesh Kumar\nHDFC Bank")
        assertEquals(QualifyVerdict.QUALIFIED, q.verdict)
        assertEquals(45000L, q.amountPaise)
    }

    @Test fun `qualify REJECTS when no action word is present at all`() {
        val q = parser.qualify("₹450\nTo Ramesh Kumar\nHDFC Bank")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
        assertNull(q.amountPaise)
    }

    @Test fun `qualify rejects a list or chat outright, even when it carries a Pay button`() {
        val chat = "Pay ₹450\nTo Ramesh Kumar\nHDFC Bank\n₹450\n3:45 pm\n₹450\n6:18 pm\nType a message"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(chat).verdict)
    }

    // ── The two screens harvested off the Pixel on 2026-08-22 ────────────────────
    // Captured live with the capture service watching, scrubbed of the real merchant, amounts and ids
    // before landing in git. Under the OLD rules both of these recorded a payment; the device run confirmed
    // both are now rejected and the ledger gained zero rows.

    @Test fun `the Google Pay chat thread that started this bug records nothing`() {
        // What he actually reported: scrolling a payment conversation booked its old payments as new ones.
        val chat = "Anand Stores\nTuesday, 12:17am\nPayment to Anand Stores\n₹450\nPaid • 18 Aug\n" +
            "You earned a voucher from the reward bazaar!\nTap to view\n12:24am\n" +
            "Payment to Anand Stores\n₹219\nPaid • 12:24am\nPay"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(chat).verdict)
        assertNull(parser.parse(a11y(chat)))
    }

    @Test fun `an old transaction's receipt records nothing — the hardest case`() {
        // Textually almost a live success screen: one amount, a "To <name>", a bank, and no list chrome in
        // sight. It is the shape behind most of the 19 phantoms. What gives it away is that it never asks
        // for anything, and that it narrates an outcome that already happened.
        val receipt = "To Anand Stores\n₹450\nCompleted\n18 Aug 2026, 12:17 am\n" +
            "State Bank of India 1234\nPayment started\nPayment received by Anand Stores\n" +
            "Purchase confirmed\nUPI transaction ID\n100000000001\nTo: Anand Stores\nGoogle transaction ID"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(receipt).verdict)
        assertNull(parser.parse(a11y(receipt)))
    }

    @Test fun `the PIN warning line is never mistaken for the payee`() {
        // "Never enter your UPI PIN to receive money" is on every in-flight screen. With a case-folded
        // payee regex it would match as payee "receive money" — making the WEAK payee requirement
        // vacuous and feeding a garbage key to the learned-rules table. A PIN screen with no real
        // "To <name>" must be rejected for the missing payee, not captured under a fake one.
        val sheet = "₹450\nHDFC Bank\nEnter your PIN\nNever enter your UPI PIN to receive money"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(sheet).verdict)
    }

    @Test fun `a bare Pay node above an unrelated amount is not an action anchor`() {
        // Node texts are newline-joined, so "Pay" (a bottom-bar button) and "₹450" (any amount node)
        // can be adjacent in the flattened text without any visual relationship. The anchor must be
        // one node: the literal button text "Pay ₹450".
        val sheet = "Pay\n₹450\nTo Ramesh Kumar\nHDFC Bank"
        assertEquals(QualifyVerdict.REJECTED, parser.qualify(sheet).verdict)
    }

    @Test fun `qualify rejects a zero amount`() {
        assertEquals(QualifyVerdict.REJECTED, parser.qualify("Pay ₹0\nTo Ramesh Kumar\nHDFC Bank").verdict)
    }

    @Test fun `qualify rejects when there is no amount at all`() {
        val q = parser.qualify("Your OTP for login is 482913. Do not share it.")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
        assertNull(q.amountPaise)
    }

    @Test fun `a PIN screen without the Pay button is rejected however complete it looks`() {
        val q = parser.qualify("₹450\nTo Ramesh Kumar\n₹1,200\nHDFC Bank\nEnter your PIN")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
    }

    // ── Phase 4 per-app parsers (PhonePe / Paytm / CRED) ──────────────────────
    // Fixtures mirror the real device-dump SHAPE (flattened, newline-joined nodes) but with FAKE name /
    // last4 and non-₹1 amounts (the real harvest was a ₹1 self-pay — non-₹1 here guards against overfit and
    // keeps the owner's name/account out of git). Each app is anchored on its literal "Pay ₹<amt>" token.

    @Test fun `phonepe confirm sheet parses amount, bank, payer last4`() {
        val p = PhonePeConfirmSheetParser()
        val sheet = "Total payable\n₹350\nClose\nBank Account\nState Bank of India\n••\n1234\n₹350\n" +
            "Add Payment Method\nPay ₹350\nPay\nXXXXXX5678\n₹\n350\nProceed To Pay"
        val q = p.qualify(sheet)
        assertEquals(QualifyVerdict.QUALIFIED, q.verdict)
        assertEquals(35000L, q.amountPaise)                 // taken from "Pay ₹350"
        val r = p.parse(a11y(sheet))!!
        assertEquals(35000L, r.amountPaise)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals("State Bank of India", r.bankLabel)
        assertEquals("1234", r.payerAccountLast4)           // from the "•• 1234" tail
        assertNull("PhonePe shows a masked number, not a name", r.payeeName)
        assertEquals("a11y-phonepe", r.parserName)
    }

    @Test fun `phonepe history list does NOT qualify (no Pay action token)`() {
        val p = PhonePeConfirmSheetParser()
        // Many ₹ amounts but no singular pay action — must be rejected (this is why we anchor on "Pay ₹").
        val list = "You : ₹500 - Sent Securely\n₹90 - Received Instantly\n₹2,000 - Failed\n₹340 - Received Instantly"
        assertEquals(QualifyVerdict.REJECTED, p.qualify(list).verdict)
        assertNull(p.parse(a11y(list)))
    }

    @Test fun `paytm instrument sheet parses amount from the Pay token despite promo amounts`() {
        val p = PaytmConfirmSheetParser()
        // The promo amounts (₹1099 / ₹21) would have broken GPay's "one distinct amount" rule — the
        // Pay-token anchor ignores them and reads the real ₹350.
        val sheet = "Travel Pass @ ₹1099\n₹21 daily SIP\nPay ₹350 from\nHDFC Bank - 1234\nPay Securely ₹350"
        val q = p.qualify(sheet)
        assertEquals(QualifyVerdict.QUALIFIED, q.verdict)
        assertEquals(35000L, q.amountPaise)
        val r = p.parse(a11y(sheet))!!
        assertEquals(35000L, r.amountPaise)
        assertEquals("HDFC", r.bankLabel)
        assertEquals("1234", r.payerAccountLast4)           // from "HDFC Bank - 1234"
        assertEquals("a11y-paytm", r.parserName)
    }

    @Test fun `paytm accepts all three Pay-token spellings`() {
        val p = PaytmConfirmSheetParser()
        for (token in listOf("Pay ₹350 from", "Pay ₹350.00", "Pay Securely ₹350")) {
            val q = p.qualify("$token\nHDFC Bank - 1234")
            assertEquals("'$token' must qualify", QualifyVerdict.QUALIFIED, q.verdict)
            assertEquals("'$token' amount", 35000L, q.amountPaise)
        }
    }

    @Test fun `cred confirm sheet parses amount, payee, bank, last4 (GPay-like)`() {
        val p = CredConfirmSheetParser()
        val sheet = "Logo\nState Bank Of India - 1234\nMasked Account Number\nClose\nPay ₹450.00\n" +
            "To Ramesh Kumar\nEnter your PIN\nNever enter your UPI PIN to receive money\n1\n2\n3\nPay"
        val q = p.qualify(sheet)
        assertEquals(QualifyVerdict.QUALIFIED, q.verdict)
        assertEquals(45000L, q.amountPaise)
        val r = p.parse(a11y(sheet))!!
        assertEquals(45000L, r.amountPaise)
        assertEquals("Ramesh Kumar", r.payeeName)            // "To Ramesh Kumar"
        assertEquals("State Bank Of India", r.bankLabel)
        assertEquals("1234", r.payerAccountLast4)
        assertEquals("a11y-cred", r.parserName)
    }

    @Test fun `cred does not mistake lowercase 'to receive money' for the payee`() {
        val p = CredConfirmSheetParser()
        val sheet = "Pay ₹450.00\nNever enter your UPI PIN to receive money\nState Bank Of India - 1234"
        assertNull("the [A-Z] first-char guard must skip 'to receive money'", p.parse(a11y(sheet))!!.payeeName)
    }

    @Test fun `new-app parsers reject a screen with no Pay token`() {
        val noPay = "₹450\nTo Ramesh Kumar\nHDFC Bank - 1234"   // a balance/detail screen, no pay action
        for (p in listOf(PhonePeConfirmSheetParser(), PaytmConfirmSheetParser(), CredConfirmSheetParser())) {
            assertEquals("${p.name} must require the Pay token", QualifyVerdict.REJECTED, p.qualify(noPay).verdict)
        }
    }
}
