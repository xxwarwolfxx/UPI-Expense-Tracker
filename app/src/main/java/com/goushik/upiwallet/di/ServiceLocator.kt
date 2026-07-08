package com.goushik.upiwallet.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.TransactionRepository
import com.goushik.upiwallet.domain.LocationSettingsStore
import com.goushik.upiwallet.domain.Reconciler
import com.goushik.upiwallet.domain.UiPrefsStore
import com.goushik.upiwallet.parse.ConfirmSheetRegistry
import com.goushik.upiwallet.parse.ParserRegistry
import com.goushik.upiwallet.util.Backup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** v1→v2: add the onboarding-captured user_profile table (replaces the old hardcoded identity constants). */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS user_profile (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "displayName TEXT NOT NULL, " +
                "ownVpasCsv TEXT NOT NULL, " +
                "onboardedAt INTEGER)",
        )
    }
}

/** v2→v3: add the per-user learned merchant→category map (Phase 3 categorization). */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS merchant_rules (" +
                "matchKey TEXT NOT NULL PRIMARY KEY, " +
                "category TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "updatedAt INTEGER NOT NULL)",
        )
    }
}

/** v3→v4: collapse to the 10-category taxonomy (slice 3b). No schema change — categories are stored as
 *  label strings — so this only remaps the three retired buckets (Fuel/Education/Cash) onto their new homes. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("transactions", "merchant_rules")) {
            db.execSQL("UPDATE $table SET category = 'Transport' WHERE category = 'Fuel'")
            db.execSQL("UPDATE $table SET category = 'Subscriptions' WHERE category = 'Education'")
            db.execSQL("UPDATE $table SET category = 'Other' WHERE category = 'Cash/Withdrawal'")
        }
    }
}

/** v4→v5: add nullable lat/lng columns for the opt-in Insights map (Slice C). All existing rows stay
 *  NULL — location capture is going-forward-only. SQLite ADD COLUMN is a cheap metadata-only change. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN latRounded REAL")
        db.execSQL("ALTER TABLE transactions ADD COLUMN lngRounded REAL")
    }
}

/** v5→v6: add the wallet-mode flag (Phase C: spend-only vs balance). DEFAULT 1 = balance mode, so
 *  existing users keep today's behaviour; new spend-only users get 0. Cheap metadata-only ADD COLUMN. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN showBalance INTEGER NOT NULL DEFAULT 1")
    }
}

/** v6→v7: add the budgets table (Phase 2). One row per period type (DAY/WEEK/MONTH); absence = no cap.
 *  The lastAlerted* columns dedup the 80%/100% nudge to once per threshold per period. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No SQL DEFAULTs: the table is created empty (no rows to backfill) and the entity declares its
        // defaults only in Kotlin, so adding `DEFAULT 0` here would mismatch Room's schema validation and
        // crash on open. Every insert supplies all columns.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS budgets (" +
                "period TEXT NOT NULL PRIMARY KEY, " +
                "limitPaise INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, " +
                "lastAlertedThreshold INTEGER NOT NULL, " +
                "lastAlertedPeriodStart INTEGER NOT NULL)",
        )
    }
}

/**
 * Manual DI (no Hilt, per project convention). Initialized once from UpiWalletApp.onCreate with the
 * application context, so background services / the SMS receiver / WorkManager can reach the DB
 * without an Activity. `appScope` is process-scoped — not tied to any service's connect/disconnect.
 */
object ServiceLocator {
    @Volatile private var initialized = false

    private lateinit var appContext: Context

    lateinit var db: AppDatabase
        private set
    lateinit var repository: TransactionRepository
        private set
    lateinit var reconciler: Reconciler
        private set
    lateinit var locationSettings: LocationSettingsStore
        private set
    lateinit var uiPrefs: UiPrefsStore
        private set

    val parserRegistry: ParserRegistry = ParserRegistry.default()
    val confirmSheets: ConfirmSheetRegistry = ConfirmSheetRegistry.default()
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "upiwallet.db",
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            ).build()
            repository = TransactionRepository(db)
            reconciler = Reconciler(db)
            locationSettings = LocationSettingsStore(context.applicationContext)
            uiPrefs = UiPrefsStore(context.applicationContext)
            initialized = true
        }
        // Balances + identity are now captured by onboarding (data/UserProfileEntity +
        // balance_anchors), not seeded here.

        // Backfill categories for rows captured before the categorizer existed (idempotent — NULL-only).
        appScope.launch { runCatching { com.goushik.upiwallet.domain.categorize.Categorization.run(repository) } }

        startAutoBackup()
    }

    /**
     * Keep an offline backup fresh so a friend's data survives an uninstall. Any change to the data
     * tables (a silent capture, a manual add, a category fix, an anchor/budget/profile edit) triggers a
     * debounced re-write of `Download/UET-backup.json` — on `appScope` (Dispatchers.IO), so it never
     * touches the capture or UI threads. Gated to after onboarding: we never overwrite a good backup with
     * an empty one on a fresh/just-reinstalled DB (before the user has restored). No network — MediaStore.
     */
    @OptIn(FlowPreview::class)
    private fun startAutoBackup() {
        appScope.launch {
            merge(
                repository.observeTransactions().map { },
                repository.observeAnchors().map { },
                repository.observeBudgets().map { },
                repository.observeProfile().map { },
            ).debounce(BACKUP_DEBOUNCE_MS).collect {
                if (repository.profile()?.onboardedAt == null) return@collect
                runCatching { Backup.writeToDownloads(appContext, db) }
                    .onFailure { Log.w("UpiWallet", "auto-backup failed: ${it.message}") }
            }
        }
    }

    private const val BACKUP_DEBOUNCE_MS = 4_000L
}
