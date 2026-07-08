package com.goushik.upiwallet.domain.categorize

/**
 * SEED merchant dictionary: India-first UPI merchants/tokens → Category. Intentionally a hand-curated
 * starter set — extended over time and overlaid by the per-user learned map (later phase). Same package
 * as Categorizer.kt, so Category is referenced directly (no import).
 *
 * Matching is first-match-wins over an ORDERED list (mirrors ParserRegistry): specific tokens precede
 * generic ones so e.g. "instamart" beats "swiggy" for "swiggyinstamart". Accuracy over coverage — when a
 * token is dangerously generic ("act", bare "hp/vi/mart"), we use its distinctive form instead and return
 * null when unsure (the caller falls back to Other + Review, so a false map is worse than a null).
 */
object MerchantDictionary {

    fun localPart(vpa: String): String =
        vpa.substringBefore('@').lowercase().trim()

    fun byExactVpa(vpa: String): Category? =
        EXACT_VPA[vpa.lowercase().trim()]

    fun byVpaToken(localPart: String): Category? =
        scan(localPart.lowercase().trim())

    fun byCounterpartyName(name: String): Category? =
        scan(name.lowercase().trim())

    /**
     * TRUE only for true merchant payment-AGGREGATOR handles (Razorpay/PayU/BillDesk/Paytm PG). Deliberately
     * conservative: a false positive dumps a real P2P payment into Review. Plain PSP/bank handles
     * (@okhdfcbank, @oksbi, @ybl, @ibl, @axl, @paytm) are NOT aggregators — note we NEVER contains("paytm").
     */
    fun isAggregatorVpa(vpa: String): Boolean {
        val v = vpa.lowercase().trim()
        val local = localPart(v)
        if (v.contains("rzp") || v.contains("razorpay")) return true
        if (v.contains("payu")) return true
        // BillDesk's ".bdsk" lives in the LOCALPART (x.bdsk@bank), so scan the whole VPA, not the handle.
        if (v.contains("billdesk") || v.contains("bdsk")) return true
        // Paytm payment-gateway / merchant forms only — plain name@paytm P2P must stay false.
        if (local.contains("paytmqr")) return true
        if (local.startsWith("paytm-") || local.startsWith("ptm")) return true
        return false
    }

    // first-match-wins over an ordered (substring -> Category) list; specific before generic.
    private fun scan(s: String): Category? {
        if (s.isEmpty()) return null
        if (EXCLUSIONS.any { s.contains(it) }) return null  // P2P wallets that collide with merchant tokens
        for ((token, cat) in TOKENS) if (s.contains(token)) return cat
        return null
    }

    // Tokens that look like a merchant brand but are actually P2P wallets — bail before the token scan.
    private val EXCLUSIONS = listOf("amazonpay")

    // Full-VPA exact matches. Kept minimal — almost everything is token-matched below.
    private val EXACT_VPA: Map<String, Category> = mapOf(
        "swiggy@axisbank" to Category.FOOD,
        "zomato@hdfcbank" to Category.FOOD,
    )

    /**
     * ORDERED token table. Each entry is matched by `contains` on the lowercased input. Specific tokens
     * (and disambiguated forms of generic ones) come first.
     */
    private val TOKENS: List<Pair<String, Category>> = listOf(
        // ---- GROCERIES (specific quick-commerce / chains before any "swiggy"/"jio"/"mart" generic) ----
        "instamart" to Category.GROCERIES,        // MUST precede "swiggy" → Food
        "blinkit" to Category.GROCERIES,
        "zepto" to Category.GROCERIES,
        "bigbasket" to Category.GROCERIES,
        "bbnow" to Category.GROCERIES,
        "jiomart" to Category.GROCERIES,           // precede bare "jio" → Bills
        "dmart" to Category.GROCERIES,
        "avenuesupermart" to Category.GROCERIES,
        "dunzo" to Category.GROCERIES,
        "supermarket" to Category.GROCERIES,
        "supermart" to Category.GROCERIES,
        "kirana" to Category.GROCERIES,
        "grocery" to Category.GROCERIES,
        "groceries" to Category.GROCERIES,
        "provision" to Category.GROCERIES,

        // ---- FOOD ----
        "swiggy" to Category.FOOD,                 // instamart already handled above
        "zomato" to Category.FOOD,
        "eatsure" to Category.FOOD,
        "dominos" to Category.FOOD,
        "mcdelivery" to Category.FOOD,
        "mcdonald" to Category.FOOD,
        "behrouz" to Category.FOOD,
        "faasos" to Category.FOOD,
        "freshmenu" to Category.FOOD,
        "restaurant" to Category.FOOD,
        "biryani" to Category.FOOD,
        "pizza" to Category.FOOD,
        "bakery" to Category.FOOD,
        "cafe" to Category.FOOD,
        "kfc" to Category.FOOD,                    // late: short token, after longer brand names

        // ---- SUBSCRIPTIONS (before "hotstar/jio" land in Bills) ----
        "jiohotstar" to Category.SUBSCRIPTIONS,
        "hotstar" to Category.SUBSCRIPTIONS,
        "netflix" to Category.SUBSCRIPTIONS,
        "spotify" to Category.SUBSCRIPTIONS,
        "youtubepremium" to Category.SUBSCRIPTIONS,
        "youtube premium" to Category.SUBSCRIPTIONS,
        "ytpremium" to Category.SUBSCRIPTIONS,
        "primevideo" to Category.SUBSCRIPTIONS,
        "appstore" to Category.SUBSCRIPTIONS,
        "googleplay" to Category.SUBSCRIPTIONS,    // never bare "google" (catches googlepay P2P)
        "google play" to Category.SUBSCRIPTIONS,
        "subscription" to Category.SUBSCRIPTIONS,

        // ---- TRANSPORT ----
        "nammayatri" to Category.TRANSPORT,
        "namma yatri" to Category.TRANSPORT,
        "rapido" to Category.TRANSPORT,
        "irctc" to Category.TRANSPORT,
        "redbus" to Category.TRANSPORT,
        "fastag" to Category.TRANSPORT,
        "metro" to Category.TRANSPORT,
        "travels" to Category.TRANSPORT,
        "uber" to Category.TRANSPORT,
        "olacabs" to Category.TRANSPORT,           // distinctive: bare "ola" ⊂ "cola"/"sholay"
        "olamoney" to Category.TRANSPORT,
        "toll" to Category.TRANSPORT,
        // dropped bare "auto" (⊂ "autopay" mandates) and "cab" (⊂ "cable" bills) — false-positive hazards

        // ---- FUEL → folds into TRANSPORT (slice 3b: 10-category taxonomy) ----
        "indianoil" to Category.TRANSPORT,
        "hp petrol" to Category.TRANSPORT,
        "hpcl" to Category.TRANSPORT,              // distinctive; never bare "hp"
        "bpcl" to Category.TRANSPORT,
        "iocl" to Category.TRANSPORT,
        "filling station" to Category.TRANSPORT,
        "petrol" to Category.TRANSPORT,
        "fuel" to Category.TRANSPORT,

        // ---- RENT ----
        "nobroker" to Category.RENT,
        "rentpay" to Category.RENT,
        "housing" to Category.RENT,
        "rent" to Category.RENT,

        // ---- ENTERTAINMENT ----
        "bookmyshow" to Category.ENTERTAINMENT,
        "pvr" to Category.ENTERTAINMENT,
        "inox" to Category.ENTERTAINMENT,
        "cinema" to Category.ENTERTAINMENT,
        "movie" to Category.ENTERTAINMENT,

        // ---- SHOPPING (amazonpay already excluded above) ----
        "flipkart" to Category.SHOPPING,
        "myntra" to Category.SHOPPING,
        "meesho" to Category.SHOPPING,
        "nykaa" to Category.SHOPPING,
        "reliancedigital" to Category.SHOPPING,
        "tatacliq" to Category.SHOPPING,
        "decathlon" to Category.SHOPPING,
        "croma" to Category.SHOPPING,
        "amazon" to Category.SHOPPING,
        "ajio" to Category.SHOPPING,
        "ikea" to Category.SHOPPING,

        // ---- HEALTH ----
        "pharmeasy" to Category.HEALTH,
        "tata1mg" to Category.HEALTH,
        "1mg" to Category.HEALTH,
        "netmeds" to Category.HEALTH,
        "medplus" to Category.HEALTH,
        "apollo" to Category.HEALTH,
        "pharmacy" to Category.HEALTH,
        "hospital" to Category.HEALTH,
        "clinic" to Category.HEALTH,
        "diagnostic" to Category.HEALTH,
        "medical" to Category.HEALTH,

        // ---- LEARNING platforms → SUBSCRIPTIONS (slice 3b: Education dropped; online-learning is a
        //      recurring digital spend. Physical-ed tokens — coaching/tuition/school/college — removed:
        //      no home in the 10, so they fall through to Other + Review for the user to place.) ----
        "byjus" to Category.SUBSCRIPTIONS,
        "unacademy" to Category.SUBSCRIPTIONS,
        "vedantu" to Category.SUBSCRIPTIONS,
        "udemy" to Category.SUBSCRIPTIONS,
        "coursera" to Category.SUBSCRIPTIONS,

        // ---- TRAVEL ----
        "makemytrip" to Category.TRAVEL,
        "goibibo" to Category.TRAVEL,
        "cleartrip" to Category.TRAVEL,
        "ixigo" to Category.TRAVEL,
        "indigo" to Category.TRAVEL,
        "airindia" to Category.TRAVEL,
        "vistara" to Category.TRAVEL,
        "spicejet" to Category.TRAVEL,
        "airbnb" to Category.TRAVEL,
        "yatra" to Category.TRAVEL,
        "hotel" to Category.TRAVEL,
        "oyo" to Category.TRAVEL,

        // ---- BILLS & UTILITIES (bare jio/vi/act forms are disambiguated; brand-collisions handled above) ----
        "bharatbillpay" to Category.BILLS,
        "bbps" to Category.BILLS,
        "electricity" to Category.BILLS,
        "bescom" to Category.BILLS,
        "tneb" to Category.BILLS,
        "broadband" to Category.BILLS,
        "postpaid" to Category.BILLS,
        "recharge" to Category.BILLS,
        "tatasky" to Category.BILLS,
        "hathway" to Category.BILLS,
        "actfibernet" to Category.BILLS,           // distinctive; never bare "act"
        "actcorp" to Category.BILLS,
        "vodafone" to Category.BILLS,              // distinctive; never bare "vi"
        "airtel" to Category.BILLS,
        "jio" to Category.BILLS,                   // jiomart/jiohotstar already handled above
        "dth" to Category.BILLS,
        // (CASH/ATM tokens removed — Cash dropped from the 10-category taxonomy in slice 3b.)
    )
}
