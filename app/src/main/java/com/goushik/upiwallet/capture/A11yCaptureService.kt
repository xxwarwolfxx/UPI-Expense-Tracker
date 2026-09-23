package com.goushik.upiwallet.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.CaptureWatch
import com.goushik.upiwallet.parse.QualifyResult
import com.goushik.upiwallet.parse.QualifyVerdict
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.util.Dbg
import com.goushik.upiwallet.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * PRIMARY capture: reads a UPI app's native confirm-sheet *text* (FLAG_SECURE blocks screenshots, not
 * a11y text). Drives a content-driven episode machine (GPay is Flutter — route changes fire
 * WINDOW_CONTENT_CHANGED, so episodes open/close on CONTENT, not window-STATE).
 *
 * §0 ownership: the window read must BELONG to the app the event came from. `rootInActiveWindow` does not
 *   guarantee that, and the gap let a notification shade and a browser page be recorded as payments —
 *   see the guard in [handleEvent], which is the fix for the 2026-08-22 phantom-payment bug.
 * §A trigger: a snapshot must QUALIFY to open an episode, and we emit at most one PENDING row per episode.
 *   Qualifying requires the screen to be ASKING for money — the literal 'Pay ₹' button, and nothing else
 *   (the UPI-PIN screen stopped being an anchor: every PIN screen without a 'Pay ₹' was an autopay mandate,
 *   whose ₹ is a limit, not a charge) — and not to be a list/history surface
 *   ([com.goushik.upiwallet.parse.ScreenShape]) or a screen reporting an outcome.
 * §B outcome: checked BEFORE qualifying, while this app has an episode open. PhonePe paints its "Payment
 *   Successful" overlay on top of the pay sheet and the tree keeps the sheet's 'Pay ₹', so qualifying first
 *   read the success screen as a new payment. GPay's success screen is an *empty* Flutter tree —
 *   indistinguishable from other empty routes — so we NEVER auto-CONFIRM from emptiness. A row is CONFIRMED
 *   only on proof from the same app, for a fresh episode, carrying this episode's own amount
 *   ([TerminalDecision]), or on a matching SMS; failure/cancel text -> DISCARDED; otherwise it stays PENDING
 *   and the ReconcileWorker ages it to UNCONFIRMED. (This is the honest limit of the a11y-only ≤₹100 floor.)
 *
 * Nothing this service reads (screen text, payees, amounts, other apps' package names) reaches the system
 * log of a release build: every such line goes through [Dbg], which is silent unless the build is
 * debuggable.
 *
 * Phase 4: the GPay-only filter is now a package-keyed registry ([ServiceLocator.confirmSheets]). All four
 * parsers (GPay / PhonePe / Paytm / CRED) are active and sampled. [learningMode] is a debug-only harvest that
 * logs an app's confirm-sheet node trees to logcat so a parser can be (re)written from real dumps.
 */
class A11yCaptureService : AccessibilityService() {

    private class Episode(val id: String, val amountPaise: Long, val openedAt: Long, val pkg: String) {
        @Volatile var txnId: String? = null
        @Volatile var pendingStatus: TxnStatus? = null
    }

    private var episode: Episode? = null
    private var lastSig = ""
    // Re-qualify guard: the last few (app, amount) captures. Covers both the sheet re-rendering during an
    // app's success animation and the case one open episode could not — see [RecentCaptures].
    private val recent = RecentCaptures(EPISODE_WINDOW_MS)

    override fun onServiceConnected() {
        connectedAt = System.currentTimeMillis()
        live = true
        Log.d(TAG, "✅ A11yCaptureService connected (UPI confirm-sheet capture).")
        // Capture is back: record it NOW (synchronously, so no tick can read a stale flag), then clear any
        // "paused" reminder + repaint the widgets (CaptureWatch).
        val app = applicationContext
        CaptureWatch.serviceConnected(app)
        ServiceLocator.appScope.launch { CaptureWatch.tick(app) }
    }

    /**
     * The switch went off (user, or the OS). This fires while the process is still alive, so it is the
     * INSTANT signal: post the reminder and flip every widget to its paused card now, rather than
     * waiting for the next sweep to notice the settings string.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        live = false
        Log.d(TAG, "⏸ A11yCaptureService unbound — capture is off.")
        val app = applicationContext
        // The flag and its exact time are written here, synchronously: even if the process dies before the
        // tick runs, the next tick (any path) still knows the precise moment capture went off.
        CaptureWatch.serviceUnbound(app)
        ServiceLocator.appScope.launch { CaptureWatch.tick(app) }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // An AccessibilityService that throws is killed by the system — capture would silently die. We read
        // arbitrary third-party app UIs, so swallow + log per event: one odd screen must never stop capture.
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            // Release builds get the exception's class only — its message or trace can quote screen text.
            Log.w(TAG, "onAccessibilityEvent threw (ignored): ${t.javaClass.simpleName}")
            Dbg.w(t) { "onAccessibilityEvent threw (ignored): ${t.message}" }
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val parser = ServiceLocator.confirmSheets.forPackage(pkg) ?: return
        val learning = learningMode
        // Battery guard: an inactive (stub) parser — none today, all four are active — would make a
        // content-change there pure noise, so skip the tree walk unless learning mode is harvesting it.
        if (!parser.active && !learning) return

        eventCount++
        lastEventAt = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return

        // ROOT-CAUSE GUARD. `rootInActiveWindow` answers "what is on screen right now?", NOT "what is on
        // THIS app's screen?" — the window it returns need not belong to `event.packageName`. That is how a
        // notification shade, a WhatsApp message and a Chrome checkout page were read as Google Pay screens
        // and booked as spends, straight past the four-app whitelist in res/xml/a11y_config.xml. The owner's
        // own stored captures proved it: 36 of the 472 screens the app had read were another app's window.
        // A null package means ownership cannot be established, which is also a reject — a missed capture is
        // recoverable from the bank SMS, an invented one is not. The guard sits BEFORE collectText, so the
        // expensive tree walk is skipped too.
        val rootPkg = root.packageName?.toString()
        if (rootPkg != pkg) {
            foreignWindows++
            Dbg.v { "foreign window ignored: event=$pkg root=$rootPkg" }
            return
        }

        // Snapshot text synchronously — AccessibilityNodeInfo is only valid during this callback;
        // only the immutable String crosses into the IO coroutine.
        val sb = StringBuilder()
        collectText(root, sb)
        val text = sb.toString().trim()
        if (text.isEmpty()) { onEmptySurface(); return }

        val sig = text.hashCode().toString()
        if (sig == lastSig) return
        lastSig = sig

        // Learning harvest (debug-only, deduped by sig above): log the structured node tree of any
        // payment-ish screen so next turn's per-app parsers can anchor on stable resource ids, not
        // fragile visible text. Tight-gated (payment-ish only) and never persisted off-device.
        if (learning && looksPaymentish(text)) dumpTree(root, pkg, eventName(event.eventType))

        // An inactive (stub) parser is harvested above but never qualifies — no half-baked episode opens.
        if (!parser.active) return

        val now = System.currentTimeMillis()
        val q = parser.qualify(text)

        // §B BEFORE §A: while this app has a payment in flight, first ask whether this screen reports how it
        // ended. Qualifying first is how PhonePe's success overlay (which keeps the sheet's 'Pay ₹' in the
        // tree) was read as a second payment — or, inside the repeat window, swallowed as a repeat so the
        // payment's own success screen could never confirm it. Other apps' screens can't resolve it
        // ([TerminalDecision] checks the package), and neither can a screen that still qualifies as a live
        // pay sheet: that is the app asking, whatever words ("Success Traders", a "Payment failed?" promo)
        // sit on it. The overlay is REJECTED by its headline, so it still resolves.
        checkTerminal(text, pkg, q, now)

        when (q.verdict) {
            QualifyVerdict.QUALIFIED, QualifyVerdict.WEAK -> {
                val amount = q.amountPaise ?: return
                if (recent.seenRecently(pkg, amount, now)) {
                    Dbg.d { "QUALIFIED repeat (amt=$amount seen <${EPISODE_WINDOW_MS}ms ago) — ignored" }
                    return
                }
                val ep = Episode(Ids.uuid7(), amount, now, pkg)
                episode = ep
                recent.remember(pkg, amount, now)
                Dbg.d { "QUALIFIED ${q.verdict} pkg=$pkg amt=$amount reason=\"${q.reason}\" ep=${ep.id}" }
                val raw = RawCapture(Source.A11Y, text, pkg, eventName(event.eventType), null, now)
                val parsed = parser.parse(raw) ?: return
                val app = applicationContext
                ServiceLocator.appScope.launch {
                    // Opt-in Insights map: a cheap cached last-known fix (rounded to ~110m), or null when
                    // off / no permission / stale. Spike-verified this reads fine on while-in-use FINE.
                    val fix = runCatching { LocationSource.currentFix(this@A11yCaptureService) }.getOrNull()
                    // The row itself. If even this fails (a full disk), there is nothing to follow up on.
                    val id = try {
                        ServiceLocator.reconciler.onConfirmSheet(parsed, text, ep.id, pkg, fix?.lat, fix?.lng)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        logStepFailure("insert", t)
                        return@launch
                    }
                    ep.txnId = id
                    // "Last payment recorded" + the not-recording counter (self-guarded, never throws).
                    CaptureWatch.noteQualifiedCapture(app)
                    // Everything below is follow-up work on a row that is already saved. Each step is
                    // guarded on its own: one failing must neither kill the process (capture with it) nor
                    // stop the steps after it.
                    ep.pendingStatus?.let { s -> guarded("status") { ServiceLocator.reconciler.setStatus(id, s) } }
                    // Categorize the new row by payee name — idempotent NULL-only sweep.
                    guarded("categorize") {
                        com.goushik.upiwallet.domain.categorize.Categorization.run(ServiceLocator.repository)
                    }
                    // Repaint placed widgets now (the service process is already awake — no new wakeup).
                    guarded("widgets") {
                        com.goushik.upiwallet.widget.WidgetUpdater.loadAndPush(this@A11yCaptureService)
                    }
                    // A fresh spend may have crossed a budget line — nudge (opt-in, deduped per period).
                    guarded("budget") {
                        com.goushik.upiwallet.domain.budget.BudgetAlerts.check(this@A11yCaptureService)
                    }
                }
            }
            QualifyVerdict.REJECTED -> Dbg.v { "REJECTED pkg=$pkg reason=\"${q.reason}\"" }
        }
    }

    private fun onEmptySurface() {
        episode?.let { Dbg.v { "empty surface while ep=${it.id} open (no proof; stays PENDING)" } }
    }

    /**
     * Resolve the open episode if [text] proves its outcome ([TerminalDecision]). [q] is this same screen's
     * verdict: a screen that qualifies as a live pay sheet is the app asking for money, never an outcome.
     */
    private fun checkTerminal(text: String, pkg: String, q: QualifyResult, now: Long) {
        val e = episode ?: return
        val status = TerminalDecision.decide(
            text, pkg, e.pkg, e.amountPaise, now - e.openedAt, TERMINAL_WINDOW_MS,
            asksToPay = q.verdict != QualifyVerdict.REJECTED,
        ) ?: return
        resolve(e, status, "terminal screen", now)
    }

    private fun resolve(e: Episode, status: TxnStatus, why: String, now: Long) {
        episode = null
        // Re-arm the repeat guard by outcome: a success holds the amount for the full window (the success
        // animation re-flashes the sheet), a failure only briefly, so an immediate retry still records.
        // The resolving screen is never the live pay sheet itself: TerminalDecision refuses those.
        recent.settle(e.pkg, e.amountPaise, now, status, sheetStillShowing = false)
        lastSig = ""    // let a later sheet (the next payment) qualify again; `recent` blocks a re-render
        e.pendingStatus = status
        Dbg.d { "episode ${e.id} -> $status ($why)" }
        e.txnId?.let { id ->
            ServiceLocator.appScope.launch { guarded("status") { ServiceLocator.reconciler.setStatus(id, status) } }
        }
    }

    /** Runs one post-capture step; a failure is logged and swallowed so the steps after it still run. */
    private inline fun guarded(step: String, block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logStepFailure(step, t)
        }
    }

    // The release line carries the step and the exception's class only: a message can quote payment data.
    private fun logStepFailure(step: String, t: Throwable) {
        Log.w(TAG, "post-capture step '$step' failed (ignored): ${t.javaClass.simpleName}")
        Dbg.w(t) { "post-capture step '$step' failed: ${t.message}" }
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    /**
     * Learning-mode harvest: print the structured node tree (resource id + class + text + desc) for a
     * payment-ish screen, chunked under logcat's ~4000-char/line cap, bracketed by clear markers so a
     * whole dump can be copied as one block. Tag [LEARN_TAG] so it greps cleanly: `adb logcat -s UpiLearn`.
     */
    private fun dumpTree(root: AccessibilityNodeInfo, pkg: String, evt: String) {
        val sb = StringBuilder()
        appendNode(root, 0, sb)
        val n = ++learnDumpCount
        // Through Dbg like every other line here: learning mode can only be switched on in a debug build,
        // and a dump is a whole payment screen, so it must never be able to reach a release log.
        Dbg.d(LEARN_TAG) { "═══ BEGIN dump #$n  pkg=$pkg  evt=$evt ═══" }
        val body = sb.toString()
        var i = 0
        while (i < body.length) {
            val end = minOf(i + LOG_CHUNK, body.length)
            Dbg.d(LEARN_TAG) { body.substring(i, end) }
            i = end
        }
        Dbg.d(LEARN_TAG) { "═══ END dump #$n  pkg=$pkg ═══" }
    }

    private fun appendNode(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        node ?: return
        val rawId = node.viewIdResourceName
        val id = rawId?.substringAfterLast('/') ?: "-"
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
        val t = node.text?.toString()?.replace("\n", "⏎")
        val cd = node.contentDescription?.toString()?.replace("\n", "⏎")
        // Print only signal-bearing nodes (an id, visible text, or a desc) — skip pure layout containers.
        if (rawId != null || t != null || cd != null) {
            sb.append("  ".repeat(depth)).append("[$cls] id=$id")
            if (t != null) sb.append(" text=\"$t\"")
            if (cd != null) sb.append(" desc=\"$cd\"")
            sb.append('\n')
        }
        for (i in 0 until node.childCount) appendNode(node.getChild(i), depth + 1, sb)
    }

    private fun eventName(t: Int) = when (t) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WIN_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WIN_CONTENT"
        else -> "evt#$t"
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        live = false
        super.onDestroy()
    }

    companion object {
        const val TAG = "UpiWallet"
        const val LEARN_TAG = "UpiLearn"
        const val EPISODE_WINDOW_MS = 30_000L

        /**
         * How long an open episode can still be resolved by a success/failure screen. Deliberately LONGER
         * than [EPISODE_WINDOW_MS]: a slow PIN entry pushes the success screen past 30s, and refusing to
         * resolve there would leave the episode open for the success animation's re-flash to book a second
         * row — one payment, two rows. The laundering the window guards against is already blocked by
         * [TerminalDecision]'s same-app, non-list and exact-amount checks. Evidence-safe: no two captures a
         * bank SMS proved real share an amount 30s-180s apart in the owner's ledger.
         */
        const val TERMINAL_WINDOW_MS = 180_000L
        private const val LOG_CHUNK = 3500

        // Live health signals read by the debug screen (same process). 0 = never seen this process —
        // the honest "is capture actually alive" check, vs the enabled-setting which lies after an update.
        @Volatile var connectedAt = 0L

        /**
         * True only while this service instance is connected IN THIS PROCESS — the one honest "is capture
         * running right now?" answer. The app has a single process, so the widgets, workers and screens that
         * ask live alongside the service. Android's own registry is not enough: on 2026-09-23 the Pixel kept
         * a killed service in its bound list (and in Settings) while no instance existed and nothing was read.
         */
        @Volatile var live = false
        @Volatile var lastEventAt = 0L
        @Volatile var eventCount = 0

        /** Screens skipped because the active window belonged to another app — the root-cause guard. */
        @Volatile var foreignWindows = 0

        // Phase 4 harvest. Process-lived (auto-off on process death / app restart), debug-toggled, so a
        // real artifact can never ship with it on. When on, payment-ish screens from the whitelisted UPI
        // apps are dumped to logcat ([dumpTree]). Toggled by the upiwallet://debug/learn adb intent
        // (debug builds only — see MainActivity.handleDebugIntent).
        @Volatile var learningMode = false
        @Volatile var learnDumpCount = 0

        // Harvest gate only — the terminal decision itself moved to [TerminalDecision], because these two
        // regexes fire on any history screen and that is how phantom rows got promoted to CONFIRMED.
        private val SUCCESS_TOKEN =
            Regex("(?i)\\b(payment successful|paid|completed|success(?:ful)?|sent successfully)\\b")
        private val FAIL_TOKEN =
            Regex("(?i)\\b(failed|declined|cancell?ed|unsuccessful|couldn'?t)\\b")
        private val AMOUNT_HINT = Regex("(?:₹|Rs\\.?|INR)\\s?[0-9]")

        /** A screen worth harvesting: shows an amount, or a success/failure outcome token. */
        private fun looksPaymentish(text: String): Boolean =
            AMOUNT_HINT.containsMatchIn(text) ||
                SUCCESS_TOKEN.containsMatchIn(text) ||
                FAIL_TOKEN.containsMatchIn(text)
    }
}
