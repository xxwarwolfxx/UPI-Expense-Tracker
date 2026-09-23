package com.goushik.upiwallet.capture

import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.parse.GpayConfirmSheetParser
import com.goushik.upiwallet.parse.PhonePeConfirmSheetParser
import com.goushik.upiwallet.parse.QualifyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The episode machine's terminal rules — the path that used to launder phantoms into CONFIRMED rows
 * (8 of the 24 fake rows in the user's ledger were CONFIRMED, not merely pending).
 */
class TerminalDecisionTest {

    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"
    private val window = A11yCaptureService.TERMINAL_WINDOW_MS   // what the service actually passes

    private fun decide(
        text: String,
        eventPkg: String = gpay,
        episodePkg: String = gpay,
        amount: Long = 8_700,
        age: Long = 2_000,
        asksToPay: Boolean = false,
    ) = TerminalDecision.decide(text, eventPkg, episodePkg, amount, age, window, asksToPay)

    @Test fun `a success screen carrying this episode's amount confirms it`() {
        assertEquals(TxnStatus.CONFIRMED, decide("Payment successful\n₹87\nTo Ramesh Kumar"))
    }

    @Test fun `the decimal spelling of the amount also confirms`() {
        assertEquals(TxnStatus.CONFIRMED, decide("Paid\n₹87.00\nTo Ramesh Kumar"))
    }

    @Test fun `an Indian-grouped amount confirms`() {
        assertEquals(TxnStatus.CONFIRMED, decide("Payment successful\n₹1,50,000", amount = 15_000_000))
    }

    @Test fun `a success screen for a DIFFERENT amount leaves the episode open`() {
        assertNull("someone else's receipt is not proof about this payment", decide("Paid\n₹450\nTo Someone"))
    }

    @Test fun `a success word with no amount at all leaves the episode open`() {
        assertNull(decide("Payment successful"))
    }

    @Test fun `a history screen never confirms, however many success words it has`() {
        // The exact failure: "paid" and "completed" litter a transaction list, and the old code confirmed on
        // either one, from any screen.
        val history = "₹87 Paid\n3:45 pm\n₹450 Paid\n4:02 pm\nUPI transaction ID"
        assertNull(decide(history))
    }

    @Test fun `proof from another app is ignored`() {
        assertNull("PhonePe cannot confirm a Google Pay episode",
            decide("Payment successful ₹87", eventPkg = phonepe))
    }

    @Test fun `an episode older than the window can no longer be resolved`() {
        assertNull(decide("Payment successful ₹87", age = window + 1))
    }

    @Test fun `a slow PIN entry can still be resolved inside the real terminal window`() {
        // The service passes TERMINAL_WINDOW_MS (3 min), not the 30s episode window. If a success screen
        // arriving at 35s could not resolve, the episode would stay open and the app's own success
        // animation would re-flash the sheet into a SECOND row for the same payment.
        assertEquals(
            TxnStatus.CONFIRMED,
            TerminalDecision.decide(
                "Payment successful ₹87", gpay, gpay, 8_700, 35_000,
                A11yCaptureService.TERMINAL_WINDOW_MS, asksToPay = false,
            ),
        )
    }

    @Test fun `a failure screen discards without needing the amount`() {
        // Deliberately laxer than confirming: a failed payment left counted inflates the totals, and the SMS
        // path flips a wrong discard back.
        assertEquals(TxnStatus.DISCARDED, decide("Payment failed"))
    }

    @Test fun `a REAL failure screen discards even though its refund wording looks like a list`() {
        // The blocker the review caught: "failed" + "refunded" is two outcome words, so the shape gate
        // would swallow a genuine failure screen — and a failed payment sends no bank SMS, meaning the
        // row would count in the totals forever. Failure must be checked before the list gate.
        val failure = "Payment failed\n₹87\nAny money debited will be refunded within 3 working days"
        assertEquals(TxnStatus.DISCARDED, decide(failure))
    }

    @Test fun `a history screen with a failure word discards the open episode — accepted trade`() {
        // Old-code parity, kept deliberately: a wrong discard on an rrn-less row is flipped back by the
        // SMS path (findDiscardedMatch), while a missed discard of a real failure never heals.
        val history = "₹87 Paid\n3:45 pm\n₹450 Failed\n4:02 pm"
        assertEquals(TxnStatus.DISCARDED, decide(history))
    }

    @Test fun `failure wins over success when both words are on screen`() {
        assertEquals(TxnStatus.DISCARDED, decide("₹87 Payment could not be completed"))
    }

    // ── the amount match must not prefix-match a bigger amount ────────────────

    @Test fun `episode ₹50 is not confirmed by a screen showing ₹50,000`() {
        assertNull(decide("Payment successful\n₹50,000", amount = 5_000))
    }

    @Test fun `episode ₹100 is not confirmed by a screen showing ₹100_50`() {
        assertNull(decide("Paid ₹100.50", amount = 10_000))
    }

    @Test fun `a lowercase rs prefix still confirms`() {
        assertEquals(TxnStatus.CONFIRMED, decide("Payment successful rs.87"))
    }

    // ── PhonePe's success overlay: the service now asks THIS before it qualifies the screen ───────────

    @Test fun `PhonePe's success overlay confirms the open episode even with the pay sheet still underneath`() {
        // Scrubbed shape of the stored captures: headline + timestamp on top, the whole sheet ("Pay ₹1"
        // included) below. Qualifying first read this as a second ₹1 payment; asked first, it is the proof.
        val overlay = "Payment Successful\n23 June 2026 at 01:47 AM\nTotal payable\n₹1\nClose\n" +
            "State Bank of India\n••\n1234\n₹1\nPay ₹1\nNavigate up\nPay\nRAMESH KUMAR\n" +
            "rameshk@okhdfcbank\n₹\n1\nAdd a message (optional)\nProceed To Pay"
        // Asked exactly as the service asks: the overlay's headline makes the screen REJECTED, not a sheet.
        val asks = PhonePeConfirmSheetParser().qualify(overlay).verdict != QualifyVerdict.REJECTED
        assertFalse(asks)
        assertEquals(
            TxnStatus.CONFIRMED,
            TerminalDecision.decide(overlay, phonepe, phonepe, 100, 8_000, window, asksToPay = asks),
        )
    }

    // ── the episode's own live sheet never resolves it, whatever words sit on it ───────────────────────

    /** Google Pay's PIN sheet for ₹450 to [payee] (scrubbed shape), plus any [extra] lines on it. */
    private fun liveSheet(payee: String, extra: String = "") =
        "Logo\nHDFC Bank\nBank Name\nMasked Account Number\nClose\nPay ₹450.00\nTo $payee\n" +
            "Enter your PIN\nNever enter your UPI PIN to receive money\n1\n2\n3\nPay" + extra

    /** What the service does: qualify the screen, then ask TerminalDecision with that verdict. */
    private fun serviceDecides(screen: String, amount: Long = 45_000): TxnStatus? {
        val asks = GpayConfirmSheetParser().qualify(screen).verdict != QualifyVerdict.REJECTED
        return decide(screen, amount = amount, asksToPay = asks)
    }

    @Test fun `a live sheet for a payee named Success Traders does not confirm its own episode`() {
        val sheet = liveSheet("Success Traders")
        assertEquals("the words alone would have confirmed it before the PIN",
            TxnStatus.CONFIRMED, decide(sheet, amount = 45_000))
        assertNull("it is still asking to pay — the row stays PENDING", serviceDecides(sheet))
    }

    @Test fun `a live sheet carrying a payment-failed promo does not discard its own episode`() {
        val sheet = liveSheet("Success Traders", "\nPayment failed? Get an instant refund")
        assertEquals("the words alone would have discarded a payment in progress",
            TxnStatus.DISCARDED, decide(sheet, amount = 45_000))
        assertNull(serviceDecides(sheet))
    }

    @Test fun `a live sheet for another amount does not resolve the open episode either`() {
        val other = liveSheet("Success Traders", "\nPayment failed? Get an instant refund").replace("₹450.00", "₹500.00")
        assertNull(serviceDecides(other, amount = 45_000))
    }

    @Test fun `a failure headline drawn over the sheet still discards`() {
        val overlay = "Payment failed\n" + liveSheet("Success Traders")
        assertEquals(TxnStatus.DISCARDED, serviceDecides(overlay))
    }

    @Test fun `a success headline drawn over the sheet still confirms`() {
        val overlay = "Payment successful\n" + liveSheet("Success Traders")
        assertEquals(TxnStatus.CONFIRMED, serviceDecides(overlay))
    }

    @Test fun `a plain pay sheet re-rendering never resolves its own episode`() {
        // The service now runs this check on every screen of the episode's app, the live sheet included —
        // so the sheet itself must be a no-op (the corpus has no live sheet carrying an outcome word).
        val sheet = "Logo\nHDFC Bank\nBank Name\nMasked Account Number\nClose\nPay ₹87.00\nTo Ramesh Kumar\n" +
            "Enter your PIN\nNever enter your UPI PIN to receive money\n1\n2\n3\nPay"
        assertNull(decide(sheet))
    }

    @Test fun `a no-break space between symbol and digits still confirms`() {
        assertEquals(TxnStatus.CONFIRMED, decide("Payment successful ₹\u00A087"))
    }
}
