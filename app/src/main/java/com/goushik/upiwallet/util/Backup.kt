package com.goushik.upiwallet.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.BudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.MerchantRuleEntity
import com.goushik.upiwallet.data.RawEventEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.review.DuplicateDetection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Full-fidelity, offline backup + restore — so a friend's data survives UNINSTALLING the app.
 *
 * Android wipes the app's private DB on uninstall, and this app has no INTERNET permission (Google Auto
 * Backup is off by design), so the only durable place is the phone's own storage. [writeToDownloads]
 * serializes all six Room tables to one JSON file in the public **Downloads** folder via MediaStore —
 * files there survive an uninstall (unlike `Android/data/<pkg>/`, which is erased) and need no
 * permission on minSdk 31. Restore is user-driven: after reinstalling, the friend picks the file (SAF)
 * and [restoreFromUri] merges it back.
 *
 * Because that file is the ONLY thing that survives an uninstall, writing it is careful: a new copy is
 * written beside the old one, read back and compared byte for byte, and only then does the old one go
 * ([writeToDownloads]). A snapshot that suddenly holds far fewer payments than the last backup never
 * replaces it ([shouldKeepPrevious]), and neither does one missing any payment the last backup held
 * ([mayReplace]). Only files in our own naming shape are ever replaced, so a copy the
 * user renamed to keep is left alone ([Naming.isOwnShape]).
 *
 * The merge can never double-count (the "totals must reconcile" guard): transactions are content-deduped
 * by [planTxnMerge] (id, RRN, and same-payment content — a11y captures have no RRN, so index-level dedupe
 * alone would re-add them under fresh ids) and anchors by [planAnchorMerge] (an account is its LABEL, and
 * newcomers can never move the balance cutoff), so existing rows win and only genuinely-new ones are
 * added. The phone's own name and UPI IDs win over the file's ([planProfileMerge]); budgets and merchant
 * rules are upserted so the backup restores them. The decisions are pure (org.json only) and unit-tested;
 * the Android I/O (MediaStore, SAF, PackageManager) sits in the suspend helpers.
 *
 * Tradeoff (accepted): the backup is plain, unencrypted JSON in Downloads on the owner's own phone. It
 * holds more than the ledger: the full text of every captured payment screen and every bank SMS (account
 * tail, balance, reference number), and the rounded (~110 m) location of each payment when location pins
 * are on. Other ordinary apps can't read it under scoped storage, but a file manager with "All files
 * access" or a computer plugged in over USB can. Nothing ever leaves the device by itself (no network, no
 * cloud), which keeps the app's core promise; the Settings copy tells the user what the file holds.
 */
object Backup {

    /** The release app's file name. MediaStore may store it as "UET-backup (N).json" — see [Naming]. */
    const val FILE_NAME = "UET-backup.json"

    /** On-disk JSON shape version — bump only on an incompatible layout change. */
    private const val FORMAT = 1

    /** An in-memory snapshot of every table. */
    data class Snapshot(
        val transactions: List<TransactionEntity>,
        val rawEvents: List<RawEventEntity>,
        val anchors: List<BalanceAnchorEntity>,
        val profile: UserProfileEntity?,
        val merchantRules: List<MerchantRuleEntity>,
        val budgets: List<BudgetEntity>,
    )

    /**
     * What a restore actually did: rows *added* vs *skipped as already present* for the merge-deduped
     * tables (transactions, anchors), and rows *applied* for the small config tables where the backup
     * simply wins (rules, budgets). Skipped is user-facing — the toast says "N already on this phone" so
     * a second restore reading "0 added" doesn't look like a failure. [keptName]/[keptUpiIds] say the
     * phone's own identity beat a different one in the file; [exportedAt]/[newestPaymentAt] let the toast
     * say how old the file was, so an old backup can't pass for a current one.
     */
    data class RestoreResult(
        val transactionsAdded: Int,
        val transactionsSkipped: Int,
        val rawEventsAdded: Int,
        val anchorsAdded: Int,
        val anchorsSkipped: Int,
        val profileApplied: Boolean,
        val merchantRulesApplied: Int,
        val budgetsApplied: Int,
        val keptName: Boolean = false,
        val keptUpiIds: Boolean = false,
        val exportedAt: Long? = null,
        val newestPaymentAt: Long? = null,
    )

    /** The transactions worth inserting from a backup, after content-dedupe against what's on the phone. */
    data class TxnMergePlan(val toInsert: List<TransactionEntity>, val skipped: Int)

    /** The profile row to write (null = leave the phone's row alone) and which identity fields the phone kept. */
    data class ProfileMerge(val profile: UserProfileEntity?, val keptName: Boolean, val keptUpiIds: Boolean)

    /** The backup's header fields, read before importing. */
    data class Header(val appVersion: String?, val dbVersion: Int, val exportedAt: Long?)

    /** ±3 min — the a11y↔SMS capture skew. Deliberately tighter than Review's ±10 min twin window:
     *  at import we silently drop rows, and a genuine repeat payment (chai ×2) must survive. */
    private const val CONTENT_WINDOW_MS = 3 * 60_000L

    // ─────────────────────────────── file naming (pure) ───────────────────────────────

    /**
     * How one build names its backup file. MediaStore resolves a name clash by renaming our new file to
     * "base (N).json" (after a reinstall the old install's file still holds the plain name), so that is
     * the only shape we treat as ours — never a prefix match, which also caught a copy the user renamed
     * to keep (e.g. "UET-backup-before-1.1.7.json") and overwrote it with live data.
     */
    class Naming(val base: String) {
        val fileName: String get() = "$base.json"

        /** SQL LIKE pre-filter for the MediaStore query; [isOwnShape] is the real test. */
        val likePattern: String get() = "$base%.json"

        private val ownShape = Regex("^" + Regex.escape(base) + """( \(\d+\))?\.json$""")

        fun isOwnShape(displayName: String): Boolean = ownShape.matches(displayName)

        /** A dated name for a snapshot that must not replace the last backup ([shouldKeepPrevious]). */
        fun datedName(now: Long, zone: ZoneId): String =
            "$base-" + Instant.ofEpochMilli(now).atZone(zone).format(DATED) + ".json"
    }

    private val DATED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    val RELEASE_NAMING = Naming("UET-backup")

    /**
     * The side-by-side test copy (the debug build) writes under an unmistakable name, so its file can
     * never be confused with the real app's in the restore picker. Its prefix doesn't start with
     * "UET-backup", so neither build ever matches the other's files.
     */
    val TEST_COPY_NAMING = Naming("UET-TEST-COPY-backup")

    fun naming(testCopy: Boolean): Naming = if (testCopy) TEST_COPY_NAMING else RELEASE_NAMING

    /**
     * The never-shrink rule: keep the previous backup untouched (and write this snapshot to a new dated
     * file instead) when the new one has no payments or fewer than half of what the last backup held.
     * Payments are only ever soft-removed (a status change, the row stays), so a real ledger never shrinks
     * like that; a database that was emptied (SQLite corruption, the debug sample seeder) does — and that
     * is exactly the moment the file is the only copy left.
     */
    fun shouldKeepPrevious(newCount: Int, previousCount: Int?): Boolean =
        previousCount != null && previousCount > 0 && (newCount == 0 || newCount * 2 < previousCount)

    /** How many transactions a backup document holds, or null if it isn't readable as one. */
    fun countTransactions(text: String): Int? =
        runCatching { JSONObject(text).optJSONArray("transactions")?.length() ?: 0 }.getOrNull()

    /** Every transaction id in a backup document, or null if it isn't readable as one. */
    fun transactionIds(text: String): Set<String>? = runCatching {
        val arr = JSONObject(text).optJSONArray("transactions") ?: return@runCatching emptySet()
        (0 until arr.length()).mapTo(HashSet()) { arr.getJSONObject(it).getString("id") }
    }.getOrNull()

    /**
     * The same-ledger rule, beside never-shrink: may the new snapshot replace (or tidy away) an older file of
     * ours whose contents are [previousText]? Only when every payment in that file is also in the snapshot.
     * Payments are only ever soft-removed, so a later state of the SAME ledger always holds them all. A
     * database recreated in place (SQLite corruption, then a fresh setup instead of a restore) or a different
     * ledger never does, however many rows it has grown to; the count rule alone ([shouldKeepPrevious]) let
     * such a snapshot replace the only copy of the old history once it reached half its size.
     *
     * A file that reads but isn't a backup (cut short by an old build) restores nothing, so it may go, as
     * before; otherwise it would block backups for good. [previousText] null = it couldn't be read just now:
     * keep it this time and look again on the next write.
     */
    fun mayReplace(previousText: String?, snapshotIds: Set<String>): Boolean {
        if (previousText == null) return false
        val ids = transactionIds(previousText) ?: return true
        return snapshotIds.containsAll(ids)
    }

    // ─────────────────────────────── read the DB ───────────────────────────────

    suspend fun snapshot(db: AppDatabase): Snapshot = Snapshot(
        transactions = db.transactionDao().allOnce(),
        rawEvents = db.rawEventDao().all(),
        anchors = db.balanceAnchorDao().all(),
        profile = db.userProfileDao().get(),
        merchantRules = db.merchantRuleDao().all(),
        budgets = db.budgetDao().all(),
    )

    // ─────────────────────────────── encode (pure) ───────────────────────────────

    fun encode(snap: Snapshot, appVersion: String, dbVersion: Int, exportedAt: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", "UPI Expense Tracker")
        root.put("appVersion", appVersion)
        root.put("dbVersion", dbVersion)
        root.put("exportedAt", exportedAt)
        root.put("transactions", JSONArray().apply { snap.transactions.forEach { put(txnToJson(it)) } })
        root.put("rawEvents", JSONArray().apply { snap.rawEvents.forEach { put(rawToJson(it)) } })
        root.put("anchors", JSONArray().apply { snap.anchors.forEach { put(anchorToJson(it)) } })
        snap.profile?.let { root.put("profile", profileToJson(it)) }
        root.put("merchantRules", JSONArray().apply { snap.merchantRules.forEach { put(ruleToJson(it)) } })
        root.put("budgets", JSONArray().apply { snap.budgets.forEach { put(budgetToJson(it)) } })
        return root.toString(2)
    }

    // ─────────────────────────────── decode (pure) ───────────────────────────────

    /** Parse a backup document. Throws [IllegalArgumentException] if it isn't one (wrong/foreign file). */
    fun decode(text: String): Snapshot = decodeRoot(JSONObject(text))

    private fun decodeRoot(root: JSONObject): Snapshot {
        require(root.optInt("format", -1) == FORMAT) {
            "This file isn't a UPI Expense Tracker backup."
        }
        return Snapshot(
            transactions = root.optJSONArray("transactions").mapObjects { txnFromJson(it) },
            rawEvents = root.optJSONArray("rawEvents").mapObjects { rawFromJson(it) },
            anchors = root.optJSONArray("anchors").mapObjects { anchorFromJson(it) },
            profile = root.optJSONObject("profile")?.let { profileFromJson(it) },
            merchantRules = root.optJSONArray("merchantRules").mapObjects { ruleFromJson(it) },
            budgets = root.optJSONArray("budgets").mapObjects { budgetFromJson(it) },
        )
    }

    /** The schema version stamped in a backup — read before importing to reject a newer-than-us file. */
    fun dbVersionOf(text: String): Int = header(text).dbVersion

    fun header(text: String): Header = headerOf(JSONObject(text))

    private fun headerOf(root: JSONObject) = Header(
        appVersion = root.stringOrNull("appVersion"),
        dbVersion = root.optInt("dbVersion", 0),
        exportedAt = root.longOrNull("exportedAt"),
    )

    /** When the newest payment in a snapshot happened (removed rows don't count), for the restore toast. */
    fun newestPaymentAt(snap: Snapshot): Long? =
        snap.transactions.filter { it.status != TxnStatus.DISCARDED }.maxOfOrNull { it.timestampEvent }

    /**
     * Why a picked file must not be restored, or null when it may. A file from a newer database schema
     * can't be read safely. A release build refuses a file the test copy made (its appVersion carries the
     * debug build's "-debug" suffix): that is a separately captured ledger, and merging it into the real
     * one re-adds payments only the test copy recorded and double-counts any it holds under another id.
     * The test copy itself may restore the real app's file — that's how it gets real data to test on.
     */
    fun restoreRefusal(header: Header, currentDbVersion: Int, thisIsTestCopy: Boolean): String? = when {
        header.dbVersion > currentDbVersion ->
            "This backup is from a newer version of the app. Please update first."
        !thisIsTestCopy && header.appVersion?.endsWith("-debug") == true ->
            "That file was made by the test copy of the app, not by UPI ET, so nothing was restored. " +
                "Pick your newest UET-backup file instead."
        else -> null
    }

    // ─────────────────────────────── merge planning (pure) ───────────────────────────────

    /**
     * Which backup transactions to actually insert. The PK/UNIQUE(rrn,direction) indexes already stop
     * exact-id and same-RRN copies, but a payment captured on THIS phone and again in the backup carries
     * a different UUID — and a11y-only captures have no RRN (SQLite treats NULLs as distinct), so without
     * content-dedupe the same payment lands twice. Skip an incoming row when:
     *  1. its id is already present (on the phone, or earlier in this same file);
     *  2. its (rrn, direction) is already present (rrn != null), likewise;
     *  3. it has NO rrn and a row ON THE PHONE matches its exact content — (direction, amount,
     *     event-timestamp, payee);
     *  4. it has NO rrn and a *live* row ON THE PHONE matches direction + amount within ±3 min AND both
     *     payee keys resolve and match. Both-must-resolve is stricter than Review's twin rule on
     *     purpose: silently dropping a genuine payment is the worse failure on a ledger that's ~90% P2P.
     * Content tiers (3–4) never touch a row that carries an RRN: the RRN is the bank's own uniqueness
     * proof, so two same-payee same-amount rows minutes apart with different RRNs are two real payments
     * (device-verified on the owner's ledger — two distinct ₹1 credits 2 min apart).
     *
     * The content tiers compare only against the phone, never against rows accepted earlier from the same
     * file. The file is one ledger that already kept those rows apart — two sub-₹100 payments to one stall
     * two minutes apart (no bank SMS, so no RRN) are two payments, and restoring onto an empty phone must
     * give back exactly what was backed up. Review's own twin check still shows any genuine duplicate the
     * source ledger held. The id/RRN tiers do run across the file, so a doubled file collapses to one copy.
     */
    fun planTxnMerge(existing: List<TransactionEntity>, incoming: List<TransactionEntity>): TxnMergePlan {
        val ids = existing.mapTo(HashSet()) { it.id }
        val rrns = existing.filter { it.rrn != null }.mapTo(HashSet()) { it.rrn to it.direction }
        val exact = existing.mapTo(HashSet()) { contentKey(it) }
        val live = HashMap<Pair<Direction, Long>, MutableList<TransactionEntity>>()
        for (t in existing) if (t.status != TxnStatus.DISCARDED) {
            live.getOrPut(t.direction to t.amountPaise) { mutableListOf() }.add(t)
        }

        val toInsert = mutableListOf<TransactionEntity>()
        for (t in incoming.sortedBy { it.timestampEvent }) {
            val duplicate = t.id in ids ||
                (t.rrn != null && (t.rrn to t.direction) in rrns) ||
                (t.rrn == null && (contentKey(t) in exact || isWindowedTwin(t, live[t.direction to t.amountPaise])))
            if (duplicate) continue
            toInsert += t
            ids += t.id
            if (t.rrn != null) rrns += t.rrn to t.direction
        }
        return TxnMergePlan(toInsert, skipped = incoming.size - toInsert.size)
    }

    private fun contentKey(t: TransactionEntity) =
        listOf(t.direction, t.amountPaise, t.timestampEvent, DuplicateDetection.payeeKey(t))

    private fun isWindowedTwin(t: TransactionEntity, candidates: List<TransactionEntity>?): Boolean {
        if (candidates == null) return false
        val key = DuplicateDetection.payeeKey(t) ?: return false
        return candidates.any {
            DuplicateDetection.payeeKey(it) == key &&
                abs(it.timestampEvent - t.timestampEvent) <= CONTENT_WINDOW_MS
        }
    }

    /**
     * Which backup raw events to insert. One whose transaction was skipped as a duplicate (and isn't on
     * the phone under that id either) would sit in the table pointing at nothing, so it's left out. A raw
     * event with no transaction at all is kept: those are the screens and texts capture looked at and
     * turned down, the evidence the capture tests replay.
     */
    fun planRawMerge(
        phoneTxnIds: Set<String>,
        insertedTxnIds: Set<String>,
        incoming: List<RawEventEntity>,
    ): List<RawEventEntity> =
        incoming.filter { r -> r.txnId == null || r.txnId in phoneTxnIds || r.txnId in insertedTxnIds }

    /**
     * The profile to write on restore. The phone's own identity wins: if it already has a name, or UPI
     * IDs, those stay, and the file only fills fields the phone left empty. A backup is a snapshot of the
     * past — restoring one must not quietly swap a corrected identity back to an old one (the owner's
     * files still carry two sample UPI IDs, and own UPI IDs decide what counts as a self-transfer). The
     * wallet mode stays the phone's too, and an existing onboardedAt is never cleared (that would send the
     * user back through setup).
     *
     * With no profile on the phone (a restore from the Welcome screen), the file's profile is taken
     * whole — except that [holdOnboarding] clears onboardedAt, so the app stays in setup until the capture
     * steps are done; the flow sets it when they are. A file with no profile still leaves a blank row in
     * that case, so a restore interrupted before setup finishes is still recognisable as one.
     */
    fun planProfileMerge(
        phone: UserProfileEntity?,
        incoming: UserProfileEntity?,
        holdOnboarding: Boolean,
        showBalanceIfNew: Boolean = false,
    ): ProfileMerge {
        if (phone == null) {
            val base = incoming
                ?: if (holdOnboarding) {
                    UserProfileEntity(displayName = "", ownVpasCsv = "", onboardedAt = null, showBalance = showBalanceIfNew)
                } else {
                    return ProfileMerge(null, keptName = false, keptUpiIds = false)
                }
            val profile = if (holdOnboarding) base.copy(onboardedAt = null) else base
            return ProfileMerge(profile, keptName = false, keptUpiIds = false)
        }
        if (incoming == null) return ProfileMerge(null, keptName = false, keptUpiIds = false)

        val phoneHasName = phone.displayName.isNotBlank()
        val phoneHasVpas = phone.ownVpaSet().isNotEmpty()
        val merged = phone.copy(
            displayName = if (phoneHasName) phone.displayName else incoming.displayName,
            ownVpasCsv = if (phoneHasVpas) phone.ownVpasCsv else incoming.ownVpasCsv,
            onboardedAt = phone.onboardedAt ?: if (holdOnboarding) null else incoming.onboardedAt,
        )
        return ProfileMerge(
            profile = merged,
            keptName = phoneHasName && incoming.displayName.isNotBlank() &&
                phone.ownNameSet() != incoming.ownNameSet(),
            keptUpiIds = phoneHasVpas && incoming.ownVpaSet().isNotEmpty() &&
                phone.ownVpaSet() != incoming.ownVpaSet(),
        )
    }

    /**
     * Which backup anchors (bank accounts) to insert. Anchors carry fresh UUIDs each time onboarding or
     * "Update balance" writes them, so id-keyed upsert alone re-adds the same bank as a duplicate row and
     * double-counts its baseline. Merge rule: an account is its label —
     *  - skip any incoming anchor whose trimmed, case-folded label (or id) already exists; the device's
     *    balance is the fresher truth, and this makes restore idempotent;
     *  - collapse label-duplicates *within* the backup itself (first wins);
     *  - when the device already has anchors, surviving newcomers are re-stamped to the device's cutoff
     *    (max anchoredAt) — the [BalanceCalculator.anchorStampFor] rule — so a restore adds exactly its
     *    baselines and can never move the global cutoff and re-cut every account's history.
     * Restoring into an EMPTY device keeps the backup's anchors verbatim (full fidelity).
     */
    fun planAnchorMerge(
        existing: List<BalanceAnchorEntity>,
        incoming: List<BalanceAnchorEntity>,
    ): List<BalanceAnchorEntity> {
        val labels = existing.mapTo(HashSet()) { normLabel(it.accountLabel) }
        val ids = existing.mapTo(HashSet()) { it.id }
        val survivors = mutableListOf<BalanceAnchorEntity>()
        for (a in incoming) {
            val label = normLabel(a.accountLabel)
            if (label in labels || a.id in ids) continue
            labels += label
            survivors += a
        }
        val cutoff = existing.maxOfOrNull { it.anchoredAt } ?: return survivors
        return survivors.map { it.copy(anchoredAt = cutoff) }
    }

    private fun normLabel(s: String) = s.trim().lowercase()

    // ─────────────────────────────── what the toasts say (pure) ───────────────────────────────

    /**
     * The restore toast. It always says how old the file is — the backup's own date and its newest
     * payment — because after a reinstall the plain "UET-backup.json" is the OLD install's frozen file
     * and a months-stale restore otherwise reads exactly like a current one.
     */
    fun restoreSummary(r: RestoreResult, dateLabel: (Long) -> String = ::shortDate): String {
        val made = r.exportedAt?.let { "a backup made ${dateLabel(it)}" } ?: "the backup"
        val newest = r.newestPaymentAt?.let { ", newest payment ${dateLabel(it)}" } ?: ""
        val total = r.transactionsAdded + r.transactionsSkipped
        val main = when {
            total == 0 -> "Restored $made with no payments in it."
            r.transactionsAdded == 0 ->
                "Nothing new to add: every payment in $made$newest is already on this phone."
            else -> {
                val already = if (r.transactionsSkipped > 0) " ${r.transactionsSkipped} were already on this phone." else ""
                "Restored ${plural(r.transactionsAdded, "payment")} from $made$newest.$already"
            }
        }
        val kept = when {
            r.keptName && r.keptUpiIds -> " Kept your current name and UPI IDs."
            r.keptName -> " Kept your current name."
            r.keptUpiIds -> " Kept your current UPI IDs."
            else -> ""
        }
        return main + kept
    }

    /** The "Back up now" toast, naming the file MediaStore actually wrote — never a hard-coded name. */
    fun savedMessage(fileName: String, keptOlder: String?): String =
        if (keptOlder == null) {
            "Backup saved to Downloads/$fileName"
        } else {
            "Backup saved to Downloads/$fileName. Your older $keptOlder holds far more payments, so it was kept as it is."
        }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** "today" / "yesterday" / "14 Jul" / "14 Jul 2025" — reads naturally mid-sentence. */
    private fun shortDate(epochMs: Long): String =
        DateTime.sectionLabel(epochMs).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }

    // ─────────────────────────────── import into the DB ───────────────────────────────

    /**
     * Merge a snapshot into the live DB in one transaction. Transactions/raw-events and anchors are
     * content-deduped (existing wins — see [planTxnMerge]/[planAnchorMerge]); the phone's identity wins
     * ([planProfileMerge]); budgets and merchant rules upsert (backup wins). Returns honest counts.
     */
    suspend fun import(db: AppDatabase, snap: Snapshot, holdOnboarding: Boolean = false): RestoreResult =
        db.withTransaction {
            val txnDao = db.transactionDao()
            val rawDao = db.rawEventDao()
            val anchorDao = db.balanceAnchorDao()
            val profileDao = db.userProfileDao()
            val ruleDao = db.merchantRuleDao()
            val budgetDao = db.budgetDao()

            val phoneTxns = txnDao.allOnce()
            val txnPlan = planTxnMerge(phoneTxns, snap.transactions)
            val inserted = HashSet<String>()
            for (t in txnPlan.toInsert) if (txnDao.insert(t) != -1L) inserted += t.id
            var addedRaw = 0
            val rawPlan = planRawMerge(phoneTxns.mapTo(HashSet()) { it.id }, inserted, snap.rawEvents)
            for (r in rawPlan) if (rawDao.insertIgnore(r) != -1L) addedRaw++
            val anchorPlan = planAnchorMerge(anchorDao.all(), snap.anchors)
            for (a in anchorPlan) anchorDao.upsert(a)
            for (m in snap.merchantRules) ruleDao.upsert(m)
            for (b in snap.budgets) budgetDao.upsert(b)
            val profilePlan = planProfileMerge(
                profileDao.get(), snap.profile, holdOnboarding,
                showBalanceIfNew = snap.anchors.isNotEmpty(),
            )
            profilePlan.profile?.let { profileDao.upsert(it) }

            RestoreResult(
                transactionsAdded = inserted.size,
                transactionsSkipped = snap.transactions.size - inserted.size,
                rawEventsAdded = addedRaw,
                anchorsAdded = anchorPlan.size,
                anchorsSkipped = snap.anchors.size - anchorPlan.size,
                profileApplied = profilePlan.profile != null,
                merchantRulesApplied = snap.merchantRules.size,
                budgetsApplied = snap.budgets.size,
                keptName = profilePlan.keptName,
                keptUpiIds = profilePlan.keptUpiIds,
            )
        }

    // ─────────────────────────────── Android I/O: restore ───────────────────────────────

    /**
     * Read a user-picked (SAF) backup file and merge it. Throws with a user-facing message if it isn't a
     * valid backup, is from a newer schema, or (in a release build) was made by the test copy.
     * [holdOnboarding] is for the Welcome screen: the data comes back but setup continues at the capture
     * steps, because a reinstall resets the Accessibility unlock and SMS permission.
     */
    suspend fun restoreFromUri(
        context: Context,
        db: AppDatabase,
        uri: Uri,
        holdOnboarding: Boolean = false,
    ): RestoreResult {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Couldn't open that file.")
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("This file isn't a UPI Expense Tracker backup.") }
        val header = headerOf(root)
        val current = db.openHelper.readableDatabase.version
        restoreRefusal(header, current, isTestCopy(context))?.let { throw IllegalArgumentException(it) }
        val snap = decodeRoot(root)
        return import(db, snap, holdOnboarding).copy(
            exportedAt = header.exportedAt,
            newestPaymentAt = newestPaymentAt(snap),
        )
    }

    // ─────────────────────────────── Android I/O: write ───────────────────────────────

    /** What a successful write produced: the file's real name, and the older file kept by never-shrink. */
    data class WriteResult(val uri: Uri, val fileName: String, val transactions: Int, val keptOlder: String?)

    /** Last-backup state for Settings ("Last backup: 2 min ago" / "Backup failed"). */
    data class BackupStatus(
        val lastSuccessAt: Long,
        val lastFailureAt: Long,
        val fileName: String?,
        val transactions: Int,
        val keptOlderFile: String?,
    ) {
        /** The most recent attempt failed (or none has succeeded yet and one failed). */
        val failing: Boolean get() = lastFailureAt > lastSuccessAt
    }

    /** One writer at a time: auto-backup and "Back up now" must never interleave on the same files. */
    private val writeLock = Mutex()

    private val statusState = MutableStateFlow<BackupStatus?>(null)

    /** Live last-backup state, loaded from prefs on first use and updated after every attempt. */
    fun observeStatus(context: Context): StateFlow<BackupStatus?> {
        if (statusState.value == null) statusState.value = BackupPrefs(context).status()
        return statusState.asStateFlow()
    }

    /**
     * Snapshot → encode → write to Downloads, without ever putting the only good copy at risk:
     *  1. a NEW pending MediaStore entry is written (the old file is untouched, and a pending entry is
     *     invisible to other apps and expires by itself if the process dies mid-write);
     *  2. it's read back and compared byte for byte, then published;
     *  3. only then is the previous file deleted, and the new one takes its name back.
     * The old in-place "wt" rewrite truncated the file first — a full phone or a killed process left an
     * empty or cut-off backup that restore rejects.
     *
     * Everything runs under one lock, snapshot included, so the later writer always writes the fresher
     * data. The file replaced is the one this install last wrote (remembered in prefs), or else the newest
     * of our own files in the [Naming.isOwnShape] shape; a file the user renamed is never touched. If the
     * new snapshot would shrink the backup ([shouldKeepPrevious]), or lacks a payment the backup holds
     * ([mayReplace] — a different ledger), it goes to a dated file instead and the old one is kept. Success
     * and failure are both recorded for Settings.
     */
    suspend fun writeToDownloads(context: Context, db: AppDatabase): WriteResult = writeLock.withLock {
        val prefs = BackupPrefs(context)
        val now = System.currentTimeMillis()
        try {
            val snap = snapshot(db)
            val dbVersion = db.openHelper.readableDatabase.version
            val bytes = encode(snap, appVersion(context), dbVersion, now).toByteArray(Charsets.UTF_8)
            val count = snap.transactions.size
            val naming = naming(isTestCopy(context))
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val own = ownFiles(resolver, collection, naming)
            val remembered = prefs.liveUri?.let { u -> own.firstOrNull { sameEntry(it.uri, Uri.parse(u)) } }
            val primary = remembered ?: own.firstOrNull()
            val previousText = primary?.let { readText(resolver, it.uri) }
            val previousCount = when {
                primary == null -> null
                remembered != null && prefs.liveCount >= 0 -> prefs.liveCount
                else -> previousText?.let { countTransactions(it) }
            }
            val snapshotIds = snap.transactions.mapTo(HashSet()) { it.id }
            // Never-shrink AND same-ledger: the previous file is replaced only by a later state of itself.
            val keepPrevious = shouldKeepPrevious(count, previousCount) ||
                (primary != null && !mayReplace(previousText, snapshotIds))

            val result = if (keepPrevious) {
                // Keep the bigger backup as it is. Reuse one dated file for as long as this lasts, so a
                // shrunken database writing every few seconds doesn't spawn a new file each time.
                val quarantine = prefs.quarantineUri?.let { u ->
                    fileAt(resolver, Uri.parse(u))?.takeIf { it.name == prefs.quarantineName }
                }
                val written = writeReplacing(
                    resolver, collection, bytes,
                    replace = quarantine,
                    requestedName = quarantine?.name ?: naming.datedName(now, ZoneId.systemDefault()),
                )
                prefs.recordQuarantine(written.uri, written.name)
                WriteResult(written.uri, written.name, count, keptOlder = primary!!.name)
            } else {
                val written = writeReplacing(
                    resolver, collection, bytes,
                    replace = primary,
                    requestedName = naming.fileName,
                )
                // Converge: any other file of ours in the backup shape is usually an older auto-backup of
                // this same install (e.g. left by a crash between writing and deleting), and rows are never
                // hard-deleted, so the copy just verified holds everything it does. Checked, not assumed:
                // a file holding a payment the snapshot lacks is another ledger's, and stays ([mayReplace]).
                for (f in own) {
                    val done = sameEntry(f.uri, written.uri) || (primary != null && sameEntry(f.uri, primary.uri))
                    if (!done && mayReplace(readText(resolver, f.uri), snapshotIds)) {
                        runCatching { resolver.delete(f.uri, null, null) }
                    }
                }
                prefs.recordLive(written.uri, written.name, count)
                WriteResult(written.uri, written.name, count, keptOlder = null)
            }
            prefs.recordSuccess(now, result.fileName, count, result.keptOlder)
            statusState.value = prefs.status()
            result
        } catch (t: Throwable) {
            prefs.recordFailure(now)
            statusState.value = prefs.status()
            throw t
        }
    }

    private class OwnFile(val uri: Uri, val name: String)

    /** Same MediaStore row? The insert result and a collection query can spell the Uri differently. */
    private fun sameEntry(a: Uri, b: Uri): Boolean =
        runCatching { ContentUris.parseId(a) == ContentUris.parseId(b) }.getOrDefault(a == b)

    /**
     * Insert a pending entry, write and verify it, publish it, and only then delete [replace] and give the
     * new file its name. Any failure before publishing removes the new entry and leaves [replace] as is.
     */
    private fun writeReplacing(
        resolver: ContentResolver,
        collection: Uri,
        bytes: ByteArray,
        replace: OwnFile?,
        requestedName: String,
    ): OwnFile {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, requestedName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the backup file.")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("Couldn't write the backup file.")
            // Verify while still pending when the platform lets us read it back; otherwise publish first
            // and verify right after — either way before anything old is deleted.
            val pendingCheck = runCatching { readBytes(resolver, uri) }
            if (pendingCheck.isSuccess) {
                check(pendingCheck.getOrNull()?.contentEquals(bytes) == true) { "The backup didn't read back intact." }
                publish(resolver, uri)
            } else {
                publish(resolver, uri)
                check(readBytes(resolver, uri)?.contentEquals(bytes) == true) { "The backup didn't read back intact." }
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        if (replace != null) {
            val deleted = runCatching { resolver.delete(replace.uri, null, null) > 0 }.getOrDefault(false)
            // Take the old file's name back, so the file the user knows keeps its name. Cosmetic: if the
            // rename is refused, the toast and Settings report whatever name the file really has.
            if (deleted && displayNameOf(resolver, uri) != replace.name) {
                runCatching {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, replace.name) }, null, null)
                }
            }
        }
        return OwnFile(uri, displayNameOf(resolver, uri) ?: requestedName)
    }

    private fun publish(resolver: ContentResolver, uri: Uri) {
        val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        check(published > 0) { "Couldn't finish the backup file." }
    }

    private fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        resolver.openInputStream(uri)?.use { it.readBytes() }

    private fun readText(resolver: ContentResolver, uri: Uri): String? =
        runCatching { readBytes(resolver, uri)?.toString(Charsets.UTF_8) }.getOrNull()

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? = fileAt(resolver, uri)?.name

    /** The file at [uri] if it still exists (and is ours to see), with its current display name. */
    private fun fileAt(resolver: ContentResolver, uri: Uri): OwnFile? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { OwnFile(uri, it) } else null
            }
        }.getOrNull()

    /**
     * Our own backup files, newest first. Scoped storage only returns rows THIS install created — after a
     * reinstall the old file isn't ours, so MediaStore stores our new one as "UET-backup (1).json" and so
     * on. Only that exact shape counts ([Naming.isOwnShape]): a file the user renamed to keep a copy
     * ("UET-backup-before-1.1.7.json") is theirs, never overwritten.
     */
    private fun ownFiles(resolver: ContentResolver, collection: Uri, naming: Naming): List<OwnFile> {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val order = "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        val out = mutableListOf<OwnFile>()
        resolver.query(collection, projection, selection, arrayOf(naming.likePattern), order)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (naming.isOwnShape(name)) out += OwnFile(ContentUris.withAppendedId(collection, c.getLong(0)), name)
            }
        }
        return out
    }

    /** The debug build is the side-by-side test copy (its own applicationId); release is the real app. */
    fun isTestCopy(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"

    /**
     * Where the write path remembers which file is ours and how the last attempt went. Plain
     * SharedPreferences: they survive the database being emptied (the never-shrink case) and are wiped
     * with the app, exactly when MediaStore stops counting the old files as ours anyway.
     */
    private class BackupPrefs(context: Context) {
        private val p: SharedPreferences =
            context.applicationContext.getSharedPreferences("backup_state", Context.MODE_PRIVATE)

        val liveUri: String? get() = p.getString(KEY_LIVE_URI, null)
        val liveCount: Int get() = p.getInt(KEY_LIVE_COUNT, -1)
        val quarantineUri: String? get() = p.getString(KEY_QUARANTINE_URI, null)
        val quarantineName: String? get() = p.getString(KEY_QUARANTINE_NAME, null)

        fun recordLive(uri: Uri, name: String, count: Int) {
            p.edit()
                .putString(KEY_LIVE_URI, uri.toString())
                .putString(KEY_LIVE_NAME, name)
                .putInt(KEY_LIVE_COUNT, count)
                // Back to normal: a dated file from an earlier shrink is left alone from now on.
                .remove(KEY_QUARANTINE_URI)
                .remove(KEY_QUARANTINE_NAME)
                .apply()
        }

        /** A shrunken snapshot's dated file. The live count stays the kept file's, so the rule holds. */
        fun recordQuarantine(uri: Uri, name: String) {
            p.edit().putString(KEY_QUARANTINE_URI, uri.toString()).putString(KEY_QUARANTINE_NAME, name).apply()
        }

        fun recordSuccess(at: Long, name: String, count: Int, keptOlder: String?) {
            val e = p.edit()
                .putLong(KEY_LAST_SUCCESS_AT, at)
                .putString(KEY_LAST_NAME, name)
                .putInt(KEY_LAST_COUNT, count)
            if (keptOlder != null) e.putString(KEY_KEPT_OLDER, keptOlder) else e.remove(KEY_KEPT_OLDER)
            e.apply()
        }

        fun recordFailure(at: Long) {
            p.edit().putLong(KEY_LAST_FAILURE_AT, at).apply()
        }

        fun status() = BackupStatus(
            lastSuccessAt = p.getLong(KEY_LAST_SUCCESS_AT, 0L),
            lastFailureAt = p.getLong(KEY_LAST_FAILURE_AT, 0L),
            fileName = p.getString(KEY_LAST_NAME, null),
            transactions = p.getInt(KEY_LAST_COUNT, 0),
            keptOlderFile = p.getString(KEY_KEPT_OLDER, null),
        )

        private companion object {
            const val KEY_LIVE_URI = "live_uri"
            const val KEY_LIVE_NAME = "live_name"
            const val KEY_LIVE_COUNT = "live_count"
            const val KEY_QUARANTINE_URI = "quarantine_uri"
            const val KEY_QUARANTINE_NAME = "quarantine_name"
            const val KEY_LAST_SUCCESS_AT = "last_backup_at"
            const val KEY_LAST_FAILURE_AT = "last_backup_failed_at"
            const val KEY_LAST_NAME = "last_backup_name"
            const val KEY_LAST_COUNT = "last_backup_count"
            const val KEY_KEPT_OLDER = "kept_older_name"
        }
    }

    // ─────────────────────────────── per-entity JSON ───────────────────────────────

    private fun txnToJson(t: TransactionEntity) = JSONObject().apply {
        put("id", t.id)
        put("amountPaise", t.amountPaise)
        put("direction", t.direction.name)
        put("status", t.status.name)
        putNullable("payeeName", t.payeeName)
        putNullable("payeeVpa", t.payeeVpa)
        putNullable("payerAccountLast4", t.payerAccountLast4)
        putNullable("bankLabel", t.bankLabel)
        putNullable("rrn", t.rrn)
        put("timestampEvent", t.timestampEvent)
        put("timestampCaptured", t.timestampCaptured)
        put("source", t.source)
        putNullable("episodeId", t.episodeId)
        put("needsReview", t.needsReview)
        putNullable("category", t.category)
        putNullable("categoryConfidence", t.categoryConfidence?.toDouble())
        putNullable("categorySource", t.categorySource)
        putNullable("latRounded", t.latRounded)
        putNullable("lngRounded", t.lngRounded)
    }

    private fun txnFromJson(o: JSONObject) = TransactionEntity(
        id = o.getString("id"),
        amountPaise = o.getLong("amountPaise"),
        direction = Direction.valueOf(o.getString("direction")),
        status = TxnStatus.valueOf(o.getString("status")),
        payeeName = o.stringOrNull("payeeName"),
        payeeVpa = o.stringOrNull("payeeVpa"),
        payerAccountLast4 = o.stringOrNull("payerAccountLast4"),
        bankLabel = o.stringOrNull("bankLabel"),
        rrn = o.stringOrNull("rrn"),
        timestampEvent = o.getLong("timestampEvent"),
        timestampCaptured = o.getLong("timestampCaptured"),
        source = o.getString("source"),
        episodeId = o.stringOrNull("episodeId"),
        needsReview = o.optBoolean("needsReview", false),
        category = o.stringOrNull("category"),
        categoryConfidence = o.doubleOrNull("categoryConfidence")?.toFloat(),
        categorySource = o.stringOrNull("categorySource"),
        latRounded = o.doubleOrNull("latRounded"),
        lngRounded = o.doubleOrNull("lngRounded"),
    )

    private fun rawToJson(r: RawEventEntity) = JSONObject().apply {
        put("id", r.id)
        putNullable("txnId", r.txnId)
        put("source", r.source)
        putNullable("packageName", r.packageName)
        putNullable("eventType", r.eventType)
        put("payload", r.payload)
        put("capturedAt", r.capturedAt)
    }

    private fun rawFromJson(o: JSONObject) = RawEventEntity(
        id = o.getString("id"),
        txnId = o.stringOrNull("txnId"),
        source = o.getString("source"),
        packageName = o.stringOrNull("packageName"),
        eventType = o.stringOrNull("eventType"),
        payload = o.getString("payload"),
        capturedAt = o.getLong("capturedAt"),
    )

    private fun anchorToJson(a: BalanceAnchorEntity) = JSONObject().apply {
        put("id", a.id)
        put("accountLabel", a.accountLabel)
        put("baselinePaise", a.baselinePaise)
        put("anchoredAt", a.anchoredAt)
    }

    private fun anchorFromJson(o: JSONObject) = BalanceAnchorEntity(
        id = o.getString("id"),
        accountLabel = o.getString("accountLabel"),
        baselinePaise = o.getLong("baselinePaise"),
        anchoredAt = o.getLong("anchoredAt"),
    )

    private fun profileToJson(p: UserProfileEntity) = JSONObject().apply {
        put("id", p.id)
        put("displayName", p.displayName)
        put("ownVpasCsv", p.ownVpasCsv)
        putNullable("onboardedAt", p.onboardedAt)
        put("showBalance", p.showBalance)
    }

    private fun profileFromJson(o: JSONObject) = UserProfileEntity(
        id = o.optString("id", UserProfileEntity.SINGLETON),
        displayName = o.getString("displayName"),
        ownVpasCsv = o.getString("ownVpasCsv"),
        onboardedAt = o.longOrNull("onboardedAt"),
        showBalance = o.optBoolean("showBalance", true),
    )

    private fun ruleToJson(m: MerchantRuleEntity) = JSONObject().apply {
        put("matchKey", m.matchKey)
        put("category", m.category)
        put("source", m.source)
        put("updatedAt", m.updatedAt)
    }

    private fun ruleFromJson(o: JSONObject) = MerchantRuleEntity(
        matchKey = o.getString("matchKey"),
        category = o.getString("category"),
        source = o.getString("source"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun budgetToJson(b: BudgetEntity) = JSONObject().apply {
        put("period", b.period)
        put("limitPaise", b.limitPaise)
        put("updatedAt", b.updatedAt)
        put("lastAlertedThreshold", b.lastAlertedThreshold)
        put("lastAlertedPeriodStart", b.lastAlertedPeriodStart)
    }

    private fun budgetFromJson(o: JSONObject) = BudgetEntity(
        period = o.getString("period"),
        limitPaise = o.getLong("limitPaise"),
        updatedAt = o.getLong("updatedAt"),
        lastAlertedThreshold = o.optInt("lastAlertedThreshold", 0),
        lastAlertedPeriodStart = o.optLong("lastAlertedPeriodStart", 0L),
    )

    // ─────────────────────────────── JSON helpers ───────────────────────────────

    /** put a value, or JSON null when it's Kotlin null (so decode's isNull() reads back a real null). */
    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.longOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.doubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { transform(getJSONObject(it)) }
    }
}
