package com.goushik.upiwallet.parse

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host unit tests for the a11y UPI confirm-sheet parser ([GpayConfirmSheetParser]).
 *
 * The real input is a [RawCapture] (source + joined node texts), NOT a bare string. The parser routes
 * on `source == Source.A11Y`, qualifies the text (needs exactly ONE distinct amount + a payee + a payer
 * bank line), and emits a [ParsedTxn] that is always a DEBIT (a confirm sheet is a spend).
 *
 * Inputs mirror the device dumps: the nodes are newline-joined so the greedy, space-allowing PAYEE
 * capture (`To <name>`) is bounded by the newline (its char class excludes '\n'). Amounts are
 * symbol-prefixed (`₹450` / `₹1,250.00`) so [ANY_AMOUNT] sees a single distinct value.
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
        assertEquals(1, r.parserVersion)
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
        // "Rs.99" exercises the Rs\.? alternation in both PAY_AMOUNT and ANY_AMOUNT.
        val r = parser.parse(a11y("Pay Rs.99\nTo Local Kirana\nSBI"))
        assertNotNull(r)
        assertEquals(9900L, r!!.amountPaise)
        assertEquals("Local Kirana", r.payeeName)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals("SBI", r.bankLabel)
    }

    // ── parse(): a WEAK sheet (no literal "Pay") is captured, not dropped ──────

    @Test fun `single amount plus payee plus bank but no Pay-anchor is still parsed (WEAK, not null)`() {
        // No literal "Pay" → WEAK verdict → parse still returns a row (captured-but-flagged), not null.
        val r = parser.parse(a11y("₹450\nTo Ramesh Kumar\nHDFC Bank"))
        assertNotNull("WEAK is captured-but-flagged, not dropped", r)
        assertEquals(45000L, r!!.amountPaise)
        assertEquals("Ramesh Kumar", r.payeeName)
        assertEquals(Direction.DEBIT, r.direction)
    }

    // ── parse(): rejections → null ────────────────────────────────────────────

    @Test fun `a non-payment OTP message with no amount parses to null`() {
        val r = parser.parse(a11y("Your OTP for login is 482913. Do not share it with anyone."))
        assertNull("no ₹/Rs/INR amount → REJECTED → null", r)
    }

    @Test fun `a list with multiple distinct amounts is rejected as not-a-sheet`() {
        // Two different currency-prefixed amounts ⇒ a history/list, not a single confirm sheet.
        val r = parser.parse(a11y("₹450\nTo Ramesh Kumar\n₹1,200\nHDFC Bank"))
        assertNull("multiple distinct amounts → REJECTED → null", r)
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

    @Test fun `qualify reports WEAK when the literal Pay anchor is absent`() {
        val q = parser.qualify("₹450\nTo Ramesh Kumar\nHDFC Bank")
        assertEquals(QualifyVerdict.WEAK, q.verdict)
        assertEquals(45000L, q.amountPaise)              // amount still surfaced on the WEAK path
    }

    @Test fun `qualify rejects when there is no amount at all`() {
        val q = parser.qualify("Your OTP for login is 482913. Do not share it.")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
        assertNull(q.amountPaise)
    }

    @Test fun `qualify rejects multiple distinct amounts as a list`() {
        val q = parser.qualify("₹450\nTo Ramesh Kumar\n₹1,200\nHDFC Bank")
        assertEquals(QualifyVerdict.REJECTED, q.verdict)
    }

    // ── Phase 4 per-app parsers (PhonePe / Paytm / CRED) ──────────────────────
    // Fixtures mirror the real device-dump SHAPE (flattened, newline-joined nodes) but with FAKE name /
    // last4 and non-₹1 amounts (the real harvest was a ₹1 self-pay — non-₹1 here guards against overfit and
    // keeps real names/accounts out of git). Each app is anchored on its literal "Pay ₹<amt>" token.

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
