package com.goushik.upiwallet.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject

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
 * The merge can never double-count (the "totals must reconcile" guard): transactions/raw-events are
 * inserted with IGNORE — deduped by primary-key id and by the UNIQUE(rrn, direction) index — so existing
 * rows win and only genuinely-new ones are added; the small config tables (profile, anchors, budgets,
 * merchant rules) are upserted so the backup restores them. Plain [encode]/[decode] are pure (org.json
 * only) and unit-tested; the Android I/O (MediaStore, SAF, PackageManager) sits in the suspend helpers.
 *
 * Tradeoff (accepted): the backup is plain JSON readable in Downloads on the owner's own phone — nothing
 * ever leaves the device (no network, no cloud), which keeps the app's core promise intact.
 */
object Backup {

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

    /** How many rows each table actually gained on a restore (existing rows are skipped, not counted). */
    data class RestoreResult(
        val transactions: Int,
        val rawEvents: Int,
        val anchors: Int,
        val profile: Boolean,
        val merchantRules: Int,
        val budgets: Int,
    )

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
    fun decode(text: String): Snapshot {
        val root = JSONObject(text)
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
    fun dbVersionOf(text: String): Int = JSONObject(text).optInt("dbVersion", 0)

    // ─────────────────────────────── import into the DB ───────────────────────────────

    /**
     * Merge a snapshot into the live DB in one transaction. Transactions/raw-events dedup on insert
     * (existing wins); config tables upsert (backup wins). Returns per-table added counts.
     */
    suspend fun import(db: AppDatabase, snap: Snapshot): RestoreResult = db.withTransaction {
        val txnDao = db.transactionDao()
        val rawDao = db.rawEventDao()
        val anchorDao = db.balanceAnchorDao()
        val profileDao = db.userProfileDao()
        val ruleDao = db.merchantRuleDao()
        val budgetDao = db.budgetDao()

        var addedTxns = 0
        for (t in snap.transactions) if (txnDao.insert(t) != -1L) addedTxns++
        var addedRaw = 0
        for (r in snap.rawEvents) if (rawDao.insertIgnore(r) != -1L) addedRaw++
        for (a in snap.anchors) anchorDao.upsert(a)
        for (m in snap.merchantRules) ruleDao.upsert(m)
        for (b in snap.budgets) budgetDao.upsert(b)
        snap.profile?.let { profileDao.upsert(it) }

        RestoreResult(
            transactions = addedTxns,
            rawEvents = addedRaw,
            anchors = snap.anchors.size,
            profile = snap.profile != null,
            merchantRules = snap.merchantRules.size,
            budgets = snap.budgets.size,
        )
    }

    // ─────────────────────────────── Android I/O ───────────────────────────────

    /**
     * Snapshot → encode → write/overwrite `Download/UET-backup.json` via MediaStore. Overwrites the app's
     * own existing file if present (found by display name), else creates it. Returns the file's Uri.
     */
    suspend fun writeToDownloads(context: Context, db: AppDatabase): Uri {
        val dbVersion = db.openHelper.readableDatabase.version
        val json = encode(snapshot(db), appVersion(context), dbVersion, System.currentTimeMillis())

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = findOwnFile(resolver, collection) ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
            }
            resolver.insert(collection, values) ?: error("Couldn't create the backup file.")
        }
        // "wt" = truncate then write, so the file never keeps stale trailing bytes from a larger prior backup.
        resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            ?: error("Couldn't write the backup file.")
        return uri
    }

    /** Read a user-picked (SAF) backup file and merge it. Throws if it isn't a valid/newer-than-us backup. */
    suspend fun restoreFromUri(context: Context, db: AppDatabase, uri: Uri): RestoreResult {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Couldn't open that file.")
        val current = db.openHelper.readableDatabase.version
        require(dbVersionOf(text) <= current) {
            "This backup is from a newer version of the app — please update first."
        }
        return import(db, decode(text))
    }

    private fun findOwnFile(resolver: ContentResolver, collection: Uri): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        resolver.query(collection, projection, selection, arrayOf(FILE_NAME), null)?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"

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
