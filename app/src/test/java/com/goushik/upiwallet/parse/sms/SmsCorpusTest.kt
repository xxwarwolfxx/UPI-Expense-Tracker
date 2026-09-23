package com.goushik.upiwallet.parse.sms

import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.parse.ParsedTxn
import com.goushik.upiwallet.parse.ParserRegistry
import com.goushik.upiwallet.parse.RawCapture
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **The merge gate for the bank-SMS rules.** Replays the owner's real bank inbox — every HDFC/SBI-related
 * text on the phone, 2018-2026 — through the live [ParserRegistry] and checks what each template family
 * turns into.
 *
 * Why replay: the rules had only ever seen invented fixtures. The first replay showed that SBI's current
 * UPI debit alert (760 of them) had never parsed, that a lender whose name ends in "Credit" turned a loan
 * EMI into income, that card offers were booked as income and declines/collect requests as spends.
 *
 * **The corpus is deliberately NOT in git** (real payees, amounts, account tails). It lives in the main
 * checkout's gitignored `backups/sms-corpus/`, found by walking up, or via `UET_SMS_CORPUS`. Without it
 * the test skips — exactly like CaptureCorpusTest — unless `CI_REQUIRE_CORPUS` is set, when a missing
 * corpus is a hard failure. When present its size is asserted, so a silently-empty load can't pass.
 *
 * Ground truth for the invariants is written here from the templates themselves (sender header + fixed
 * bank wording), not from the parser's own regexes, so the gate can't mark its own homework. The family
 * table pins exact before/after counts for this snapshot: "before" is a frozen copy of the v1 rules
 * ([LegacyV1]), "after" is the live registry. Any future change to what a family becomes shows up here.
 *
 * Nothing personal is printed: families are described by synthetic templates, never by real text.
 */
class SmsCorpusTest {

    private val registry = ParserRegistry.default()

    private data class Sms(val index: Int, val sender: String, val body: String)

    @Test
    fun `every bank template family parses the way the pinned table says`() {
        val file = findCorpus()
        if (file == null) {
            if (System.getenv("CI_REQUIRE_CORPUS") != null) {
                fail("CI_REQUIRE_CORPUS is set but no SMS corpus was found (set UET_SMS_CORPUS or put " +
                    "backups/sms-corpus/bank-sms-*.json above the checkout)")
            }
            assumeTrue("no SMS corpus on this machine — skipping (see the KDoc)", false)
        }
        val arr = JSONObject(file!!.readText()).getJSONArray("messages")
        val all = (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Sms(i, it.optString("address"), it.optString("body")) }
        }
        assertTrue("SMS corpus loaded ${all.size} messages — too few to be the real inbox", all.size >= 13_000)

        val after = all.associateWith { parse(it) }

        // ── 1. SBI's UPI debit alerts: every one is a DEBIT with an amount and a bank reference. ──────────
        val sbiUpiDebits = all.filter { it.sender.uppercase().contains("SBIUPI") && it.body.contains("debited by") }
        assertTrue("no SBIUPI 'debited by' alerts found — the label went stale", sbiUpiDebits.size >= 700)
        val sbiBad = sbiUpiDebits.filter { s -> after[s].let { it == null || it.direction != Direction.DEBIT || it.amountPaise <= 0 || it.rrn == null } }
        assertEquals("SBIUPI debit alerts not read as DEBIT+amount+RRN: ${ids(sbiBad)}", 0, sbiBad.size)

        // ── 2. A payment to a payee whose name contains "Credit" is still money OUT. ─────────────────────
        val toCredit = all.filter { s ->
            BankSenders.bankOf(s.sender) == BankSenders.HDFC && SENT.containsMatchIn(s.body) &&
                TO_LINE.find(s.body)?.value?.contains("credit", ignoreCase = true) == true
        }
        assertTrue("the lender-named-Credit case is missing from the corpus", toCredit.isNotEmpty())
        val toCreditWrong = toCredit.filter { after[it]?.direction != Direction.DEBIT }
        assertEquals("'Sent … To <X> Credit' read as something other than DEBIT: ${ids(toCreditWrong)}", 0, toCreditWrong.size)

        // ── 3. Notices and OTPs are never transactions. ─────────────────────────────────────────────────
        val notices = all.filter { s -> NOTICE_WORDS.any { s.body.contains(it, ignoreCase = true) } }
        assertTrue("no notices found — the label went stale", notices.size >= 100)
        val noticesParsed = notices.filter { after[it] != null }
        assertEquals("future-tense notices parsed as transactions: ${ids(noticesParsed)}", 0, noticesParsed.size)
        val otps = all.filter { s -> OTP_WORD.containsMatchIn(s.body) && !s.body.contains("without PIN/OTP", true) }
        assertTrue("no OTP texts found — the label went stale", otps.isNotEmpty())
        val otpsParsed = otps.filter { after[it] != null }
        assertEquals("OTP texts parsed as transactions: ${ids(otpsParsed)}", 0, otpsParsed.size)

        // ── 4. A plain phone number is never a bank. ────────────────────────────────────────────────────
        val phones = all.filter { PHONE.matches(it.sender.trim()) }
        assertTrue("no phone-number senders in the corpus — the label went stale", phones.isNotEmpty())
        val phonesAccepted = phones.filter { after[it] != null || BankSenders.bankOf(it.sender) != null }
        assertEquals("plain-number senders accepted: ${ids(phonesAccepted)}", 0, phonesAccepted.size)

        // ── 5. The family table: exact before/after per template family. ────────────────────────────────
        val table = FAMILIES.associate { f -> f.id to Tally() }
        for (s in all) {
            val fam = FAMILIES.first { it.matches(s) }
            table.getValue(fam.id).add(LegacyV1.parse(s.sender, s.body), after[s])
        }
        val rendered = FAMILIES.joinToString("\n") { f ->
            "  %-24s %5d  before %-38s after %-38s | %s".format(
                f.id, table.getValue(f.id).n, table.getValue(f.id).before, table.getValue(f.id).after, f.template,
            )
        }
        println("SMS corpus: ${all.size} messages\n$rendered")

        // ── 6. SBI's current alert also yields its account tail always, and a payee name nearly always
        // (the misses are payees with digits or braces in the bank's own text). ─────────────────────────
        val sbiCurrent = all.filter { s -> FAMILIES.first { it.matches(s) }.id == "sbi.upi.debit.2023+" }
        val withPayee = sbiCurrent.count { after[it]?.payeeName != null }
        val withTail = sbiCurrent.count { after[it]?.payerAccountLast4 != null }
        println("SBI current UPI debit: payee read on $withPayee/${sbiCurrent.size}, account tail on $withTail")
        assertEquals("every SBI UPI debit names the account tail", sbiCurrent.size, withTail)
        assertTrue("payee read on only $withPayee of ${sbiCurrent.size}", withPayee * 100 >= sbiCurrent.size * 90)

        if (all.size == SNAPSHOT_MESSAGES) {
            val actual = FAMILIES.associate { f -> f.id to table.getValue(f.id).pinned() }
            val mismatches = PINNED.keys.union(actual.keys).filter { PINNED[it] != actual[it] }
                .joinToString("\n") { "  $it: pinned=${PINNED[it]}  actual=${actual[it]}" }
            assertTrue("the SMS family table changed for the pinned snapshot:\n$mismatches", mismatches.isEmpty())
            assertEquals("SBI current UPI debits with a payee name", 736, withPayee)
        }
    }

    private fun parse(s: Sms): ParsedTxn? =
        registry.parse(RawCapture(Source.SMS, s.body, sender = s.sender, capturedAt = 0L))

    /** Positions in the corpus file only — never text. */
    private fun ids(list: List<Sms>) = list.take(20).joinToString { "#${it.index}" } + if (list.size > 20) " …" else ""

    // ── Outcome tallies ────────────────────────────────────────────────────────────────────────────────

    private class Tally {
        var n = 0
        private val b = sortedMapOf<String, Int>()
        private val a = sortedMapOf<String, Int>()
        val before get() = render(b)
        val after get() = render(a)

        fun add(old: LegacyV1.Result?, new: ParsedTxn?) {
            n++
            val ko = old?.let { key(it.direction, it.rrn != null) } ?: "-"
            val kn = new?.let { key(it.direction, it.rrn != null) } ?: "-"
            b[ko] = (b[ko] ?: 0) + 1
            a[kn] = (a[kn] ?: 0) + 1
        }

        fun pinned() = "n=$n before=${render(b)} after=${render(a)}"
        private fun key(d: Direction, rrn: Boolean) = (if (d == Direction.DEBIT) "D" else "C") + (if (rrn) "+rrn" else "")
        private fun render(m: Map<String, Int>) = m.entries.joinToString(" ") { "${it.key}:${it.value}" }
    }

    /**
     * The v1 SMS rules (2026-06-01 → v1.1.7), frozen verbatim so the table can show what each family USED
     * to become. Do not "fix" this — it is the before-picture.
     */
    private object LegacyV1 {
        data class Result(val direction: Direction, val rrn: String?)

        private val AMOUNT = Regex("(?i)(?:Rs\\.?|INR)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
        private val DEBIT_KW = Regex("(?i)\\b(debited|sent|spent|paid|withdrawn|debit)\\b")
        private val CREDIT_KW = Regex("(?i)\\b(credited|deposited|received|credit)\\b")
        private val RRN_CTX = Regex(
            "(?i)(?:upi(?:\\s*ref(?:erence)?\\s*(?:no|id)?\\.?)?|ref(?:erence)?\\s*(?:no|id)?\\.?|rrn)[:.\\s]*([0-9]{12})\\b"
        )
        private val RRN_BARE = Regex("\\b([0-9]{12})\\b")

        fun parse(sender: String, body: String): Result? {
            for (bank in listOf("HDFC", "SBI")) {
                if (!"$sender $body".contains(bank, ignoreCase = true)) continue
                val amt = AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (amt.isNaN()) continue
                val dir = when {
                    CREDIT_KW.containsMatchIn(body) -> Direction.CREDIT
                    DEBIT_KW.containsMatchIn(body) -> Direction.DEBIT
                    else -> continue
                }
                val rrn = RRN_CTX.find(body)?.groupValues?.get(1) ?: RRN_BARE.find(body)?.groupValues?.get(1)
                return Result(dir, rrn)
            }
            return null
        }
    }

    // ── Template families (synthetic descriptions; first match wins) ───────────────────────────────────

    private class Family(val id: String, val template: String, val matches: (Sms) -> Boolean)

    private companion object {
        /** The inbox this table was measured on: 13,158 bank-related texts, pulled 2026-09-23. */
        const val SNAPSHOT_MESSAGES = 13_158

        val PHONE = Regex("^\\+?[0-9]+$")
        val SENT = Regex("^(?:Amt )?Sent Rs")
        val TO_LINE = Regex("(?im)^To .*$")
        val OTP_WORD = Regex("(?<![A-Za-z])OTP(?![A-Za-z])|one[- ]time password", RegexOption.IGNORE_CASE)
        /** The spec's own wording for "this money has not moved yet". */
        val NOTICE_WORDS = listOf(
            "is scheduled", "scheduled on", "will be debited", "will be deducted", "will be processed",
            "is due", "due on", "pre-debit",
        )

        fun hdr(s: Sms) = s.sender.uppercase()
        fun hdfc(s: Sms) = BankSenders.bankOf(s.sender) == BankSenders.HDFC
        fun sbi(s: Sms) = BankSenders.bankOf(s.sender) == BankSenders.SBI
        fun card(s: Sms) = hdr(s).contains("SBICRD") || hdr(s).contains("MYSBIC")
        fun has(s: Sms, vararg t: String) = t.any { s.body.contains(it, ignoreCase = true) }

        val FAMILIES = listOf(
            Family("phone-number", "any text from a plain phone number") { PHONE.matches(it.sender.trim()) },
            Family("non-bank-header", "shops, CRED, insurers, offers that mention HDFC/SBI") { BankSenders.bankOf(it.sender) == null },

            // HDFC
            Family("hdfc.sent.to-credit", "Sent Rs.<amt> From HDFC Bank A/C *<a> / To <Lender> Credit / On <d> / Ref <rrn>") {
                hdfc(it) && SENT.containsMatchIn(it.body) && TO_LINE.find(it.body)?.value?.contains("credit", true) == true
            },
            Family("hdfc.sent", "Sent Rs.<amt> From HDFC Bank A/C *<a> / To <payee> / On <d> / Ref <rrn>") { hdfc(it) && SENT.containsMatchIn(it.body) },
            Family("hdfc.mandate.sent", "UPI Mandate: / Sent Rs.<amt> / from HDFC Bank A/c <a> / To <merchant> / <d> / Ref <rrn>") { hdfc(it) && it.body.startsWith("UPI Mandate:") && has(it, "Sent Rs") },
            Family("hdfc.debited.upi", "HDFC Bank: Rs <amt> debited from a/c **<a> on <d> to VPA <vpa> (UPI Ref No <rrn>)") { hdfc(it) && has(it, "debited from") && has(it, "UPI Ref") },
            Family("hdfc.money-transfer", "Money Transfer:Rs <amt> from HDFC Bank A/c **<a> on <d> to <payee> UPI: <rrn>") { hdfc(it) && it.body.startsWith("Money Transfer:") },
            Family("hdfc.credit-alert", "Credit Alert! Rs.<amt> credited to HDFC Bank A/c XX<a> on <d> from VPA <vpa> (UPI <rrn>)") { hdfc(it) && it.body.startsWith("Credit Alert!") },
            Family("hdfc.credited.upi", "HDFC Bank: Rs. <amt> credited to a/c XX<a> on <d> by a/c linked to VPA <vpa> (UPI Ref No <rrn>)") { hdfc(it) && has(it, "credited to a/c") },
            Family("hdfc.received", "Money Received - INR <amt> in your HDFC Bank A/c / Received! INR <amt> in HDFC Bank A/c …") { hdfc(it) && (it.body.startsWith("Money Received") || it.body.startsWith("Received!")) },
            Family("hdfc.e-mandate.notice", "E-Mandate! Rs.<amt> will be deducted on <d> For <merchant> mandate UMN <umn>") { hdfc(it) && it.body.startsWith("E-Mandate!") },
            Family("hdfc.upcoming-mandate", "HDFC Bank: Upcoming mandate set for <d>, your account will be debited with Rs <amt> …") { hdfc(it) && has(it, "Upcoming mandate") },
            Family("hdfc.nach-deducted", "PAYMENT ALERT! INR <amt> deducted from HDFC Bank A/C No <a> towards <lender> UMRN: <umrn>") { hdfc(it) && it.body.startsWith("PAYMENT ALERT!") },
            Family("hdfc.mandate.admin", "UPI-Mandate created / Mandate Set / mandate cancelled … (no money moved)") { hdfc(it) && has(it, "mandate") && has(it, "created", "Mandate Set", "cancelled", "Cancelled") },
            Family("hdfc.collect-request", "HDFC Bank: <payee> has requested Rs. <amt> from you through UPI …") { hdfc(it) && has(it, "has requested") },
            Family("hdfc.card.spend", "You've spent / Spent Rs.<amt> … HDFC Bank (Debit) Card xx<c> at <merchant> …") { hdfc(it) && has(it, "spent", "withdrawn") && has(it, "Card") },
            Family("hdfc.card.e-mandate", "AutoPay (E-mandate) Successful/Reminder/Declined … HDFC Bank Debit Card …") { hdfc(it) && has(it, "AutoPay (E-mandate)", "E-mandate)") },
            Family("hdfc.declined-failed", "… declined / Failed! / Transaction failed … (no money moved)") { hdfc(it) && has(it, "declined", "failed") },
            Family("hdfc.loan-emi-due", "Your EMI of Rs.<amt> on HDFC Bank loan a/c no. <a> is due on <d> …") { hdfc(it) && has(it, "is due", "due on") },
            Family("hdfc.card-offer", "… HDFC Bank Credit/Debit Card … voucher / offer / cashback (marketing)") { hdfc(it) && has(it, "Credit Card", "Debit Card", "debit card", "credit card") },
            Family("hdfc.other", "everything else from HDFCBK/HDFCBN (logins, charges, KYC, reversals …)") { hdfc(it) },

            // SBI Card
            Family("sbicard.spend", "Rs.<amt> spent on your SBI (Credit) Card ending <c> at <merchant> on <d>") { sbi(it) && card(it) && has(it, "spent on your") },
            Family("sbicard.e-mandate.charged", "Transaction of Rs.<amt> at <merchant> against E-mandate … has been debited to your SBI Credit Card …") { sbi(it) && card(it) && has(it, "has been debited to your") },
            Family("sbicard.payment-received", "We have received (the) payment of Rs.<amt> via <rail> & … credited to your SBI Card") { sbi(it) && card(it) && has(it, "received payment", "received the payment") },
            Family("sbicard.refund", "Rs. <amt> has been credited to your SBI Credit Card … towards reversal/cashback …") { sbi(it) && card(it) && has(it, "reversal", "refunded") },
            Family("sbicard.due-notice", "Dear Cardholder, your payment of INR <amt> at <merchant> is due on <d> and will be processed …") { sbi(it) && card(it) && has(it, "is due", "due on", "due by", "overdue", "Amt Due", "Amount Due") },
            Family("sbicard.other", "SBI Card offers, limits, Flexipay, statements, security notes") { sbi(it) && card(it) },

            // SBI
            Family("sbi.upi.debit.2023+", "Dear UPI user A/C X<a> debited by <amt> on date <d> trf to <payee> Refno <rrn> If not u? call-…") { sbi(it) && it.body.startsWith("Dear UPI user A/C") && has(it, "debited by") },
            Family("sbi.upi.debit.2019-23", "Dear SBI (UPI) User, your A/c X<a>-debited by Rs<amt> on <d> transfer to <payee> Ref No <rrn>") { sbi(it) && has(it, "debited by Rs") },
            Family("sbi.upi.debit.2020-23", "Rs<amt> debited@SBI UPI frm A/cX<a> on <d> RefNo <rrn>") { sbi(it) && has(it, "UPI frm A/c") },
            Family("sbi.upi.debit.2018-20", "Dear SBI UPI User, your account is debited INR <amt> on Date <d> by UPI Ref No <rrn>") { sbi(it) && has(it, "account is debited INR") },
            Family("sbi.upi.credit", "Dear SBI (UPI) User, ur A/cX<a> credited by/with Rs<amt> on <d> by <payer> (Ref no <rrn>)") { sbi(it) && has(it, "credited by Rs", "credited with Rs", "-credited by Rs") },
            Family("sbi.autopay.notice", "Dear UPI User, UPI AutoPay for <merchant> debit of Rs.<amt> is scheduled on <d> …") { sbi(it) && has(it, "UPI AutoPay") },
            Family("sbi.mandate.admin", "Your UPI-Mandate for Rs.<amt> is successfully created/cancelled towards <merchant> …") { sbi(it) && has(it, "UPI-Mandate") },
            Family("sbi.imps", "Your a/c no. XX<a> is debited for / credited by Rs.<amt> … (IMPS Ref no <rrn>)") { sbi(it) && has(it, "IMPS Ref") },
            Family("sbi.cbs.debit", "Your A/C XX<a> Debited INR <amt> … / has a debit by transfer of Rs <amt> … Avl Bal …") { sbi(it) && has(it, "Debited INR", "has a debit by", "debited to account") },
            Family("sbi.cbs.credit", "Your A/C XX<a> Credited INR <amt> … / has a credit by Transfer of Rs <amt> …") { sbi(it) && has(it, "Credited INR", "has a credit by", "has credit for") },
            Family("sbi.debit-card.spend", "transaction number <n> for Rs.<amt> by SBI Debit Card X<c> … / … for a purchase worth Rs<amt> …") { sbi(it) && has(it, "transaction number", "for a purchase worth") },
            Family("sbi.debit-card.used", "SBIDrCard X<c> used for Rs<amt> on <d> at <merchant> Txn#<n> / tx# … by SBIDrCARD …") { sbi(it) && has(it, "SBIDrCard", "SBIDrCARD") },
            Family("sbi.atm", "Rs.<amt> withdrawn at SBI ATM <id> from A/cX<a> … / Rs<amt> w/d@SBI ATM …") { sbi(it) && has(it, "ATM") && has(it, "withdrawn", "w/d", "YONO Cash") },
            Family("sbi.card.e-mandate", "Payment of Rs <amt> for <service> e-mandate … processed successfully / could not be processed / is due …") { sbi(it) && has(it, "e-mandate") },
            Family("sbi.neft", "INR <amt> credited to your A/c No XX<a> … through NEFT / NEFT from Ac X<a> … sent from YONO") { sbi(it) && has(it, "NEFT") },
            Family("sbi.charges", "Your A/C ending with <a> has been debited for INR <amt> towards annual maintenance charges …") { sbi(it) && has(it, "maintenance charge") },
            Family("sbi.other", "everything else from SBI headers (OTPs, logins, YONO, offers, declines …)") { sbi(it) },
        )

        /** Measured 2026-09-23 on the snapshot above; regenerate only on purpose (the test prints the table). */
        val PINNED: Map<String, String> = mapOf(
            "phone-number" to "n=13 before=-:12 C:1 after=-:13",
            "non-bank-header" to "n=356 before=-:259 C:39 D:57 D+rrn:1 after=-:356",
            "hdfc.sent.to-credit" to "n=16 before=C+rrn:16 after=D+rrn:16",
            "hdfc.sent" to "n=4323 before=D+rrn:4323 after=D+rrn:4323",
            "hdfc.mandate.sent" to "n=191 before=D+rrn:191 after=D+rrn:191",
            "hdfc.debited.upi" to "n=1178 before=D+rrn:1178 after=D+rrn:1178",
            "hdfc.money-transfer" to "n=714 before=-:714 after=-:714",
            "hdfc.credit-alert" to "n=297 before=C+rrn:297 after=C+rrn:297",
            "hdfc.credited.upi" to "n=344 before=C+rrn:344 after=C+rrn:344",
            "hdfc.received" to "n=29 before=C+rrn:29 after=C+rrn:29",
            "hdfc.e-mandate.notice" to "n=227 before=-:227 after=-:227",
            "hdfc.upcoming-mandate" to "n=9 before=D:9 after=-:9",
            "hdfc.nach-deducted" to "n=47 before=-:47 after=-:47",
            "hdfc.mandate.admin" to "n=140 before=-:140 after=-:140",
            "hdfc.collect-request" to "n=13 before=D:13 after=-:13",
            "hdfc.card.spend" to "n=76 before=D:76 after=D:76",
            "hdfc.card.e-mandate" to "n=23 before=-:15 D:8 after=-:20 D:3",
            "hdfc.declined-failed" to "n=52 before=-:38 D:14 after=-:52",
            "hdfc.loan-emi-due" to "n=28 before=-:26 C:2 after=-:28",
            "hdfc.card-offer" to "n=326 before=-:88 C:214 D:24 after=-:324 D:2",
            "hdfc.other" to "n=335 before=-:310 C:7 D:18 after=-:316 C:4 D:15",
            "sbicard.spend" to "n=312 before=-:7 C:59 D:246 after=-:7 D:305",
            "sbicard.e-mandate.charged" to "n=207 before=C:207 after=D:207",
            "sbicard.payment-received" to "n=140 before=C:140 after=-:140",
            "sbicard.refund" to "n=13 before=C:13 after=-:13",
            "sbicard.due-notice" to "n=278 before=-:58 C:213 C+rrn:7 after=-:278",
            "sbicard.other" to "n=706 before=-:463 C:243 after=-:706",
            "sbi.upi.debit.2023+" to "n=760 before=-:760 after=D+rrn:760",
            "sbi.upi.debit.2019-23" to "n=562 before=D+rrn:562 after=D+rrn:562",
            "sbi.upi.debit.2020-23" to "n=170 before=D+rrn:170 after=D+rrn:170",
            "sbi.upi.debit.2018-20" to "n=45 before=D+rrn:45 after=D+rrn:45",
            "sbi.upi.credit" to "n=100 before=C+rrn:100 after=C+rrn:100",
            "sbi.autopay.notice" to "n=20 before=D:20 after=-:20",
            "sbi.mandate.admin" to "n=13 before=-:13 after=-:13",
            "sbi.imps" to "n=3 before=-:1 C:1 C+rrn:1 after=-:1 D:1 D+rrn:1",
            "sbi.cbs.debit" to "n=51 before=D:51 after=D:51",
            "sbi.cbs.credit" to "n=193 before=C:193 after=C:193",
            "sbi.debit-card.spend" to "n=122 before=D:94 D+rrn:28 after=D:94 D+rrn:28",
            "sbi.debit-card.used" to "n=295 before=-:295 after=-:295",
            "sbi.atm" to "n=73 before=-:71 D+rrn:2 after=-:71 D+rrn:2",
            "sbi.card.e-mandate" to "n=94 before=-:2 D:92 after=-:70 D:24",
            "sbi.neft" to "n=6 before=C:5 D:1 after=C:5 D:1",
            "sbi.charges" to "n=6 before=D:6 after=-:1 D:5",
            // D:2 were two SBIBNK reward offers ("Earn 5X points on min Rs N spent") booked as spends.
            "sbi.other" to "n=252 before=-:241 C:3 D:8 after=-:250 C:2",
        )
    }

    private fun findCorpus(): File? {
        System.getenv("UET_SMS_CORPUS")?.let { return File(it).takeIf(File::isFile) }
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            val folder = File(dir, "backups/sms-corpus")
            if (folder.isDirectory) {
                folder.listFiles { f: File -> f.isFile && f.name.startsWith("bank-sms-") && f.name.endsWith(".json") }
                    ?.maxByOrNull { it.name }
                    ?.let { return it }
            }
            dir = dir?.parentFile
        }
        return null
    }
}
