package com.goushik.upiwallet.capture

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.parse.QualifyVerdict
import com.goushik.upiwallet.parse.RawCapture
import com.goushik.upiwallet.util.Ids
import kotlinx.coroutines.launch

/**
 * PRIMARY capture: reads a UPI app's native confirm-sheet *text* (FLAG_SECURE blocks screenshots, not
 * a11y text). Drives a content-driven episode machine (GPay is Flutter — route changes fire
 * WINDOW_CONTENT_CHANGED, so episodes open/close on CONTENT, not window-STATE).
 *
 * §A trigger: a snapshot must QUALIFY (anchored 'Pay ₹', one amount, payee, bank) to open an episode,
 *   and we emit at most one PENDING row per episode (episode-scoped dedup).
 * §B success: GPay's success screen is an *empty* Flutter tree — indistinguishable from other empty
 *   routes — so we NEVER auto-CONFIRM from emptiness. A row is CONFIRMED only on a positive success
 *   token or a matching SMS; failure/cancel text -> DISCARDED; otherwise it stays PENDING and the
 *   ReconcileWorker ages it to UNCONFIRMED. (This is the honest limit of the a11y-only ≤₹100 floor.)
 *
 * Phase 4: the GPay-only filter is now a package-keyed registry ([ServiceLocator.confirmSheets]). All four
 * parsers (GPay / PhonePe / Paytm / CRED) are active and sampled. [learningMode] is a debug-only harvest that
 * logs an app's confirm-sheet node trees to logcat so a parser can be (re)written from real dumps.
 */
class A11yCaptureService : AccessibilityService() {

    private class Episode(val id: String, val amountPaise: Long, val openedAt: Long) {
        @Volatile var txnId: String? = null
        @Volatile var pendingStatus: TxnStatus? = null
    }

    private var episode: Episode? = null
    private var lastSig = ""
    // Re-render guard: the amount + time of the last resolved episode, so a confirm sheet that flashes again
    // during an app's success animation can't re-qualify the just-finished payment as a duplicate row.
    private var lastResolvedAmount: Long? = null
    private var lastResolvedAt = 0L

    override fun onServiceConnected() {
        connectedAt = System.currentTimeMillis()
        Log.d(TAG, "✅ A11yCaptureService connected (UPI confirm-sheet capture).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // An AccessibilityService that throws is killed by the system — capture would silently die. We read
        // arbitrary third-party app UIs, so swallow + log per event: one odd screen must never stop capture.
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "onAccessibilityEvent threw (ignored): ${t.message}", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val parser = ServiceLocator.confirmSheets.forPackage(pkg) ?: return
        val learning = learningMode
        // Battery guard: a stub app (PhonePe/Paytm/CRED) has no real parser yet, so a content-change there
        // is pure noise — skip the tree walk entirely unless we're actively harvesting it in learning mode.
        if (!parser.active && !learning) return

        eventCount++
        lastEventAt = System.currentTimeMillis()
        val root = rootInActiveWindow ?: return

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

        // Stub apps are harvested above but never qualify — no half-baked episode opens from them.
        if (!parser.active) return

        val q = parser.qualify(text)
        when (q.verdict) {
            QualifyVerdict.QUALIFIED, QualifyVerdict.WEAK -> {
                val amount = q.amountPaise ?: return
                val now = System.currentTimeMillis()
                val open = episode
                if (open != null && open.amountPaise == amount && now - open.openedAt < EPISODE_WINDOW_MS) {
                    Log.d(TAG, "QUALIFIED dup (same episode amt=$amount) — ignored")
                    return
                }
                // Post-resolve re-render guard: an app (e.g. PhonePe) flashes the confirm sheet again during
                // its success animation, which would re-qualify the just-finished payment. Ignore a same-amount
                // re-qualify within the episode window after a resolve — a genuine repeat is still caught by SMS.
                if (lastResolvedAmount == amount && now - lastResolvedAt < EPISODE_WINDOW_MS) {
                    Log.d(TAG, "QUALIFIED post-resolve re-render (amt=$amount) — ignored")
                    return
                }
                val ep = Episode(Ids.uuid7(), amount, now)
                episode = ep
                Log.d(TAG, "QUALIFIED ${q.verdict} pkg=$pkg amt=$amount reason=\"${q.reason}\" ep=${ep.id}")
                val raw = RawCapture(Source.A11Y, text, pkg, eventName(event.eventType), null, now)
                val parsed = parser.parse(raw) ?: return
                ServiceLocator.appScope.launch {
                    // Opt-in Insights map: a cheap cached last-known fix (rounded to ~110m), or null when
                    // off / no permission / stale. Spike-verified this reads fine on while-in-use FINE.
                    val fix = runCatching { LocationSource.currentFix(this@A11yCaptureService) }.getOrNull()
                    val id = ServiceLocator.reconciler.onConfirmSheet(parsed, text, ep.id, pkg, fix?.lat, fix?.lng)
                    ep.txnId = id
                    ep.pendingStatus?.let { ServiceLocator.reconciler.setStatus(id, it) }
                    // Categorize the new row by payee name — idempotent NULL-only sweep.
                    com.goushik.upiwallet.domain.categorize.Categorization.run(ServiceLocator.repository)
                    // Repaint placed widgets now (the service process is already awake — no new wakeup).
                    com.goushik.upiwallet.widget.WidgetUpdater.loadAndPush(this@A11yCaptureService)
                    // A fresh spend may have crossed a budget line — nudge (opt-in, deduped per period).
                    com.goushik.upiwallet.domain.budget.BudgetAlerts.check(this@A11yCaptureService)
                }
            }
            QualifyVerdict.REJECTED -> {
                Log.v(TAG, "REJECTED pkg=$pkg reason=\"${q.reason}\"")
                checkTerminal(text)
            }
        }
    }

    private fun onEmptySurface() {
        episode?.let { Log.v(TAG, "empty surface while ep=${it.id} open (no proof; stays PENDING)") }
    }

    private fun checkTerminal(text: String) {
        val e = episode ?: return
        when {
            SUCCESS_TOKEN.containsMatchIn(text) -> resolve(e, TxnStatus.CONFIRMED, "success token")
            FAIL_TOKEN.containsMatchIn(text) -> resolve(e, TxnStatus.DISCARDED, "failure token")
        }
    }

    private fun resolve(e: Episode, status: TxnStatus, why: String) {
        episode = null
        lastSig = ""    // let a later identical sheet (the next payment) qualify again
        lastResolvedAmount = e.amountPaise          // ...but NOT this amount re-rendering during success
        lastResolvedAt = System.currentTimeMillis()
        e.pendingStatus = status
        Log.d(TAG, "episode ${e.id} -> $status ($why)")
        e.txnId?.let { id -> ServiceLocator.appScope.launch { ServiceLocator.reconciler.setStatus(id, status) } }
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
        Log.d(LEARN_TAG, "═══ BEGIN dump #$n  pkg=$pkg  evt=$evt ═══")
        val body = sb.toString()
        var i = 0
        while (i < body.length) {
            val end = minOf(i + LOG_CHUNK, body.length)
            Log.d(LEARN_TAG, body.substring(i, end))
            i = end
        }
        Log.d(LEARN_TAG, "═══ END dump #$n  pkg=$pkg ═══")
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

    companion object {
        const val TAG = "UpiWallet"
        const val LEARN_TAG = "UpiLearn"
        const val EPISODE_WINDOW_MS = 30_000L
        private const val LOG_CHUNK = 3500

        // Live health signals read by the debug screen (same process). 0 = never seen this process —
        // the honest "is capture actually alive" check, vs the enabled-setting which lies after an update.
        @Volatile var connectedAt = 0L
        @Volatile var lastEventAt = 0L
        @Volatile var eventCount = 0

        // Phase 4 harvest. Process-lived (auto-off on process death / app restart), debug-toggled, so a
        // real artifact can never ship with it on. When on, payment-ish screens from the whitelisted UPI
        // apps are dumped to logcat ([dumpTree]). Toggled by the upiwallet://debug/learn adb intent
        // (debug builds only — see MainActivity.handleDebugIntent).
        @Volatile var learningMode = false
        @Volatile var learnDumpCount = 0

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
