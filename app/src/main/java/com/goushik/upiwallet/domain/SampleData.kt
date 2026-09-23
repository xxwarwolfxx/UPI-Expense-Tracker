package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.data.UserProfileEntity
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.util.Ids

/**
 * Dev-only seeder. Fills the app with realistic, deterministic sample data so the UI can be reviewed
 * end-to-end (Home wallet, Recent list, Insights chart, the Ghost-City map, the Review inbox) without
 * a live capture session.
 *
 * NOT wired into any release path — call [seed] from a debug action only. Names here are deliberately
 * fictional (Bram Stoker + invented merchants/people); they are NOT real captured data.
 *
 * Determinism: every value comes from fixed arrays indexed by row, plus a single `now` captured once.
 * No `Math.random`, no per-row clock reads — re-running produces the same fixture DATA (modulo the
 * `now` baseline, which only shifts every timestamp uniformly). Row ids are still fresh UUIDv7s via
 * `Ids.uuid7()`, which is intentional — ids needn't be reproducible.
 */
object SampleData {

    /** Madurai-ish base; spots are offsets from here so the map clusters into a handful of wells. */
    private const val BASE_LAT = 9.9252
    private const val BASE_LNG = 78.1198

    private const val DAY_MS = 86_400_000L

    /** Payee display names are intentionally ALL-CAPS to exercise the display-time title-case transform. */
    private data class Row(
        val payeeName: String,
        val rupees: Double,                 // human-readable; converted to paise on insert
        val daysAgo: Int,                   // event = now - daysAgo*DAY - hourOffset
        val hour: Int,                      // hour-of-day for time-of-day variety (0..23)
        val direction: Direction = Direction.DEBIT,
        val source: String = Source.SMS,
        val bankLabel: String = "HDFC",
        val last4: String = "1234",
        val categoryLabel: String? = null,  // Category.label (NOT lowercase, NOT enum-name); null = let app categorize
        val needsReview: Boolean = false,
        val status: TxnStatus = TxnStatus.CONFIRMED,
        val payeeVpa: String? = null,
        val spotIndex: Int = -1,            // -1 = no location; else index into SPOTS
    )

    /** ~6 distinct map spots around Madurai (±~0.03), so several pins/heat-wells appear. */
    private val SPOTS: List<Pair<Double, Double>> = listOf(
        BASE_LAT + 0.000 to BASE_LNG + 0.000,
        BASE_LAT + 0.022 to BASE_LNG - 0.018,
        BASE_LAT - 0.025 to BASE_LNG + 0.012,
        BASE_LAT + 0.015 to BASE_LNG + 0.028,
        BASE_LAT - 0.018 to BASE_LNG - 0.024,
        BASE_LAT + 0.030 to BASE_LNG + 0.005,
    )

    // Category labels, referenced by the canonical enum so a typo can't slip in and `fromLabel` always
    // resolves them at display time (the codebase persists Category.label, never .name or lowercase).
    private val FOOD = Category.FOOD.label
    private val GROCERIES = Category.GROCERIES.label
    private val TRANSPORT = Category.TRANSPORT.label
    private val SHOPPING = Category.SHOPPING.label
    private val BILLS = Category.BILLS.label
    private val HEALTH = Category.HEALTH.label
    private val ENTERTAINMENT = Category.ENTERTAINMENT.label
    private val SUBSCRIPTIONS = Category.SUBSCRIPTIONS.label
    private val SELF_TRANSFER = Category.SELF_TRANSFER.label
    private val OTHER = Category.OTHER.label

    /**
     * ~30 fixtures across the last ~45 days. ALL-CAPS payees; a mix of SMS/A11Y/MANUAL; ~12 carry a
     * Madurai location; a few left category-null + needsReview to populate the Review inbox.
     */
    private val ROWS: List<Row> = listOf(
        // ── credits / income ──
        Row("ACME PAYROLL", 6_000.00, daysAgo = 44, hour = 10, direction = Direction.CREDIT,
            source = Source.SMS, bankLabel = "HDFC", last4 = "1234", categoryLabel = OTHER),
        Row("AMAZON REFUND", 249.00, daysAgo = 12, hour = 16, direction = Direction.CREDIT,
            source = Source.SMS, bankLabel = "HDFC", last4 = "1234", categoryLabel = OTHER),

        // ── self-transfer (nets to zero; payeeVpa is an own VPA from the profile). The UI styles this
        //    via BalanceCalculator.isSelfTransfer, so the category is cosmetic — but labelling it
        //    SELF_TRANSFER makes the map well + detail read correctly without waiting for a sweep. ──
        Row("BRAM STOKER", 2_500.00, daysAgo = 30, hour = 21, source = Source.A11Y,
            bankLabel = "SBI", last4 = "0398", payeeVpa = "bramstoker@oksbi",
            categoryLabel = SELF_TRANSFER, needsReview = false, spotIndex = 0),

        // ── everyday spends ──
        Row("SWIGGY", 432.00, daysAgo = 1, hour = 13, source = Source.SMS, categoryLabel = FOOD, spotIndex = 0),
        Row("ZOMATO LIMITED", 615.50, daysAgo = 2, hour = 20, source = Source.SMS, categoryLabel = FOOD, spotIndex = 1),
        Row("BLINKIT", 287.00, daysAgo = 2, hour = 9, source = Source.A11Y, bankLabel = "SBI", last4 = "0398",
            categoryLabel = GROCERIES, spotIndex = 0),
        Row("AMAZON PAY INDIA PRIVATE", 1_299.00, daysAgo = 3, hour = 22, source = Source.SMS,
            categoryLabel = SHOPPING),
        Row("UBER INDIA SYSTEMS", 178.00, daysAgo = 3, hour = 8, source = Source.SMS,
            categoryLabel = TRANSPORT, spotIndex = 2),
        Row("RAPIDO", 64.00, daysAgo = 4, hour = 18, source = Source.SMS, bankLabel = "SBI", last4 = "0398",
            categoryLabel = TRANSPORT, spotIndex = 2),
        Row("RELIANCE RETAIL", 2_140.00, daysAgo = 5, hour = 19, source = Source.SMS,
            categoryLabel = GROCERIES, spotIndex = 3),
        Row("MORE RETAIL", 956.00, daysAgo = 6, hour = 11, source = Source.A11Y, bankLabel = "SBI", last4 = "0398",
            categoryLabel = GROCERIES, spotIndex = 3),
        Row("INDIAN OIL", 2_000.00, daysAgo = 7, hour = 7, source = Source.SMS,
            categoryLabel = TRANSPORT, spotIndex = 4),
        Row("APOLLO PHARMACY", 540.00, daysAgo = 8, hour = 17, source = Source.SMS,
            categoryLabel = HEALTH, spotIndex = 1),
        Row("RAMESH KUMAR", 1_500.00, daysAgo = 9, hour = 12, source = Source.A11Y, bankLabel = "SBI",
            last4 = "0398", categoryLabel = null, needsReview = true, spotIndex = 5), // person → Review
        Row("PRIYA SHARMA", 350.00, daysAgo = 10, hour = 14, source = Source.A11Y, last4 = "1234",
            categoryLabel = null, needsReview = true), // person → Review
        Row("DECATHLON SPORTS", 3_499.00, daysAgo = 11, hour = 16, source = Source.SMS,
            categoryLabel = SHOPPING, spotIndex = 5),
        Row("BIGBASKET", 1_120.00, daysAgo = 13, hour = 10, source = Source.SMS,
            categoryLabel = GROCERIES, spotIndex = 0),
        Row("NETFLIX", 199.00, daysAgo = 14, hour = 23, source = Source.SMS, categoryLabel = SUBSCRIPTIONS),
        Row("SPOTIFY INDIA", 119.00, daysAgo = 14, hour = 23, source = Source.SMS, bankLabel = "SBI",
            last4 = "0398", categoryLabel = SUBSCRIPTIONS),
        Row("AIRTEL", 799.00, daysAgo = 15, hour = 9, source = Source.SMS, categoryLabel = BILLS),
        Row("TATA POWER", 1_640.00, daysAgo = 16, hour = 11, source = Source.SMS, categoryLabel = BILLS),
        Row("STARBUCKS COFFEE", 470.00, daysAgo = 18, hour = 15, source = Source.A11Y, last4 = "1234",
            categoryLabel = FOOD, spotIndex = 1),
        Row("MEESHO", 640.00, daysAgo = 20, hour = 21, source = Source.SMS, categoryLabel = SHOPPING),
        Row("DOMINOS PIZZA", 729.00, daysAgo = 22, hour = 20, source = Source.SMS,
            categoryLabel = FOOD, spotIndex = 4),
        Row("PVR CINEMAS", 880.00, daysAgo = 24, hour = 19, source = Source.SMS,
            categoryLabel = ENTERTAINMENT, spotIndex = 3),
        Row("CULT FITNESS", 1_499.00, daysAgo = 26, hour = 6, source = Source.MANUAL, last4 = "1234",
            categoryLabel = HEALTH), // MANUAL → no rrn
        Row("CHENNAI SILKS", 4_250.00, daysAgo = 28, hour = 13, source = Source.SMS,
            categoryLabel = SHOPPING, spotIndex = 5),
        Row("LOCAL KIRANA STORE", 88.00, daysAgo = 32, hour = 18, source = Source.A11Y, bankLabel = "SBI",
            last4 = "0398", categoryLabel = null, needsReview = true, status = TxnStatus.PENDING), // PENDING + Review
        Row("JIO RECHARGE", 239.00, daysAgo = 35, hour = 10, source = Source.SMS, categoryLabel = BILLS),
        Row("HALDIRAM SWEETS", 410.00, daysAgo = 40, hour = 17, source = Source.MANUAL, last4 = "1234",
            categoryLabel = FOOD), // MANUAL → no rrn
        Row("CAFE COFFEE DAY", 265.00, daysAgo = 43, hour = 16, source = Source.A11Y, last4 = "1234",
            categoryLabel = FOOD, spotIndex = 2),
    )

    /** The seeder's two sample UPI IDs, written into the profile by [seed]. A real profile holding either
     *  one means the sample data leaked into a real install (see domain/review/SampleRows). */
    const val SAMPLE_OWN_VPAS = "bramstoker@oksbi,bramstoker@okhdfcbank"

    /**
     * What one seeded row looks like, so a later check can recognise the seeder's rows among real ones
     * (domain/review/SampleRows). Built from the same [ROWS] and the same helpers [seed] uses, so the two
     * can never drift apart.
     */
    data class Fingerprint(
        val payeeName: String,
        val amountPaise: Long,
        val direction: Direction,
        val source: String,
        /** The seeder's own made-up reference for an SMS fixture; null for screen/manual fixtures. */
        val rrn: String?,
        /** How long before the seed's `now` the row is dated — identical for every row of one seed run. */
        val offsetMs: Long,
    )

    val FINGERPRINTS: List<Fingerprint> by lazy {
        ROWS.mapIndexed { i, r -> Fingerprint(r.payeeName, paiseOf(r), r.direction, r.source, rrnFor(i, r), offsetOf(r)) }
    }

    private fun paiseOf(r: Row): Long = Math.round(r.rupees * 100.0)

    private fun offsetOf(r: Row): Long = r.daysAgo * DAY_MS + r.hour * 3_600_000L

    /** SMS rows get a distinct 12-digit RRN; the UNIQUE(rrn,direction) index drops dup RRNs silently
     *  (OnConflictStrategy.IGNORE), so each must be unique. a11y/manual rows keep null. */
    private fun rrnFor(index: Int, r: Row): String? =
        if (r.source == Source.SMS) "4%011d".format(index.toLong()) else null

    /**
     * Wipe the DB and reseed deterministic sample data.
     *
     * Clearing: uses Room's built-in [AppDatabase.clearAllTables] — there is no per-table delete on
     * TransactionDao to call instead, and this file may not add one. clearAllTables empties EVERY table
     * (transactions, balance_anchors, user_profile, AND raw_events + merchant_rules). Wiping the learned
     * merchant_rules is deliberate: stale rules would otherwise re-tag these fixtures on the next sweep.
     * It manages its own transaction, so it is NOT wrapped in withTransaction.
     */
    suspend fun seed(db: AppDatabase) {
        val now = System.currentTimeMillis()

        // 1. clear everything (single built-in call; replaces a per-table delete this file can't add).
        db.clearAllTables()

        val txnDao = db.transactionDao()
        val anchorDao = db.balanceAnchorDao()
        val profileDao = db.userProfileDao()

        // 2. profile — drives self-transfer netting (own VPA / display name) and onboarding gate.
        profileDao.upsert(
            UserProfileEntity(
                displayName = "Bram Stoker",
                ownVpasCsv = SAMPLE_OWN_VPAS,
                onboardedAt = now,
            ),
        )

        // 3. balance anchors — anchored 60 days back so every sample spend lands at/after the anchor
        //    and is counted by BalanceCalculator's at/after-cutoff netting.
        val anchoredAt = now - 60 * DAY_MS
        anchorDao.upsert(BalanceAnchorEntity(Ids.uuid7(), "HDFC", 42_30_000L, anchoredAt)) // ₹42,300.00
        anchorDao.upsert(BalanceAnchorEntity(Ids.uuid7(), "SBI", 18_90_000L, anchoredAt))  // ₹18,900.00

        // 4. transactions — deterministic, indexed.
        ROWS.forEachIndexed { i, r ->
            val eventAt = now - offsetOf(r)
            val rrn = rrnFor(i, r)
            val loc = if (r.spotIndex in SPOTS.indices) SPOTS[r.spotIndex] else null

            txnDao.insert(
                TransactionEntity(
                    id = Ids.uuid7(),
                    amountPaise = paiseOf(r),
                    direction = r.direction,
                    status = r.status,
                    payeeName = r.payeeName,
                    payeeVpa = r.payeeVpa,
                    payerAccountLast4 = r.last4,
                    bankLabel = r.bankLabel,
                    rrn = rrn,
                    timestampEvent = eventAt,
                    timestampCaptured = eventAt,
                    source = r.source,
                    needsReview = r.needsReview,
                    category = r.categoryLabel,
                    categoryConfidence = if (r.categoryLabel != null) 1.0f else null,
                    categorySource = if (r.categoryLabel != null) "manual" else null,
                    latRounded = loc?.first,
                    lngRounded = loc?.second,
                ),
            )
        }
    }
}
