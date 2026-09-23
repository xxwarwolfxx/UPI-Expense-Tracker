package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.Reconciler
import com.goushik.upiwallet.parse.ParsedTxn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules that let a bank alert sent hours late claim its own pay screen — and nothing else. */
class LateAlertMatchTest {

    private fun same(smsName: String?, rowName: String?, smsVpa: String? = null, rowVpa: String? = null) =
        LateAlertMatch.samePayee(smsName, smsVpa, rowName, rowVpa)

    @Test fun `the same name matches whatever the case or punctuation`() {
        assertTrue(same("KESTREL STORES", "Kestrel Stores"))
        assertTrue(same("Kestrel-Stores.", "KESTREL  STORES"))
    }

    @Test fun `a name the bank cut short still matches the full name on the screen`() {
        // HDFC cuts the payee at 25 characters, mid-word.
        assertTrue(same("NIMBUS Groceries Private Li", "Nimbus Groceries Private Limited"))
    }

    @Test fun `a name the app shortened still matches the bank's registered name`() {
        assertTrue(same("KESTREL STORES PRIVATE LIMITED", "Kestrel Stores"))
    }

    @Test fun `a different payee never matches`() {
        assertFalse(same("JUNIPER TRADERS", "Marigold Traders"))
        assertFalse("a shared first word is not the same payee", same("Juniper Pan Shop", "Juniper Traders"))
    }

    @Test fun `a prefix too short to say anything does not match`() {
        assertFalse(same("Jun", "Juniper Traders"))
        assertTrue(same("Juni", "Juniper Traders"))
    }

    @Test fun `an unknown payee on either side is not a match`() {
        assertFalse(same(null, "Kestrel Stores"))
        assertFalse(same("KESTREL STORES", null))
        assertFalse(same(" . ", "Kestrel Stores"))
    }

    @Test fun `when both sides carry a UPI ID, its user part decides`() {
        assertTrue(same(null, null, smsVpa = "kestrelstores@okaxis", rowVpa = "KestrelStores@ybl"))
        assertFalse("different IDs are different payees, even under one name",
            same("Kestrel Stores", "Kestrel Stores", smsVpa = "kestrel1@okaxis", rowVpa = "kestrel2@okaxis"))
        assertTrue("an ID on one side only falls back to the names",
            same("Kestrel Stores", "Kestrel Stores", smsVpa = "kestrelstores@okaxis"))
    }

    @Test fun `the late window reaches three hours before the send time, plus the clock skew after it`() {
        val sentAt = 1_790_000_000_000L
        assertEquals(
            SmsTiming.Window(sentAt - 3 * 60 * 60_000L, sentAt + SmsTiming.CLOCK_SKEW_MS, sentAt),
            LateAlertMatch.window(sentAt),
        )
    }

    // ── Reconciler.lateAlertFits: the payee rule plus the bank rule ──────────────────────────────────

    private fun sms(payee: String?, bank: String?) = ParsedTxn(
        amountPaise = 500_000, direction = Direction.DEBIT, payeeName = payee, bankLabel = bank,
        rrn = "123456789012", parserName = "test", parserVersion = 1,
    )

    private fun screen(payee: String?, bank: String?) = TransactionEntity(
        id = "r", amountPaise = 500_000, direction = Direction.DEBIT, status = TxnStatus.UNCONFIRMED,
        payeeName = payee, bankLabel = bank, timestampEvent = 0, timestampCaptured = 0, source = Source.A11Y,
    )

    @Test fun `the same payee from the same bank fits, however each side spells the bank`() {
        assertTrue(Reconciler.lateAlertFits(sms("KESTREL ST", BankSenders.SBI), screen("Kestrel Stores", "State Bank of India")))
        assertTrue(Reconciler.lateAlertFits(sms("KESTREL ST", BankSenders.SBI), screen("Kestrel Stores", "State Bank Of India")))
        assertTrue(Reconciler.lateAlertFits(sms("Kestrel Stores", BankSenders.HDFC), screen("Kestrel Stores", "HDFC")))
    }

    @Test fun `a screen row paid from another bank never fits`() {
        assertFalse(Reconciler.lateAlertFits(sms("Kestrel Stores", BankSenders.HDFC), screen("Kestrel Stores", "State Bank of India")))
    }

    @Test fun `an unknown bank on the screen leaves it to the payee`() {
        assertTrue(Reconciler.lateAlertFits(sms("Kestrel Stores", BankSenders.HDFC), screen("Kestrel Stores", null)))
        assertFalse(Reconciler.lateAlertFits(sms("Marigold Traders", BankSenders.HDFC), screen("Kestrel Stores", null)))
    }
}
