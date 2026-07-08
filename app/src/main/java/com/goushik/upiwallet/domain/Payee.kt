package com.goushik.upiwallet.domain

/**
 * A coarse, best-effort guess at whether a captured payee is an **individual person** (a P2P payment) or a
 * **business / merchant**, from the payee name / VPA alone. UPI carries no "is-merchant" flag, so this is a
 * heuristic — it never blocks anything and falls back to [PayeeKind.UNKNOWN] when unsure.
 *
 * Used in two places that must agree:
 *  - the **recents glyph** — a person icon for people, a storefront icon for businesses; and
 *  - the **review flag** — a payment to an individual is uncategorised but NOT "unsure", so the categorizer
 *    stops auto-flagging it for review (only opaque merchants/aggregators stay flagged).
 */
enum class PayeeKind { PERSON, BUSINESS, UNKNOWN }

object Payee {

    /** Word tokens that strongly mark an organisation. Matched at word boundaries (lowercased) so "india"
     *  hits "SMFG India Credit" but not "Indira", and "mart" doesn't fire on a name ending in "-mart". */
    private val BUSINESS_HINTS = setOf(
        "ltd", "limited", "pvt", "private", "llp", "inc", "corp", "corporation", "co", "company",
        "technologies", "technology", "solutions", "systems", "infotech", "labs", "software",
        "services", "industries", "enterprises", "ventures", "traders", "trading", "agency", "agencies",
        "marketplace", "retail", "store", "stores", "mart", "supermarket", "bazaar", "general", "provision",
        "foods", "restaurant", "hotel", "hotels", "resorts", "cafe", "kitchen", "bakery",
        "finserv", "finance", "financial", "capital", "credit", "lending", "loans", "bank", "insurance", "mutual",
        "india", "online", "digital", "global", "international", "group", "holdings",
        "hospital", "clinic", "pharmacy", "pharma", "medical", "diagnostics", "healthcare",
        "petroleum", "fuels", "petrol", "motors", "automobiles", "auto", "communications", "telecom", "broadband",
        "networks", "energy", "power", "electricity", "gas", "water", "utilities",
        "foundation", "trust", "society", "association", "club", "academy", "institute", "college", "school",
        "studios", "productions", "media", "entertainment", "travels", "tours", "logistics", "couriers",
        "estates", "realty", "builders", "constructions", "developers", "textiles", "jewellers", "electronics",
    )

    /** The best-effort classification. */
    fun kind(payeeName: String?, payeeVpa: String? = null): PayeeKind {
        val name = payeeName?.trim()
        if (!name.isNullOrEmpty()) {
            val lower = name.lowercase()
            if (lower.contains('&')) return PayeeKind.BUSINESS
            val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.any { it.trim('.', ',') in BUSINESS_HINTS }) return PayeeKind.BUSINESS
            // 1–4 mostly-alphabetic words, no org token → an individual's name.
            if (words.size in 1..4 && words.all { w -> w.all { c -> c.isLetter() || c == '.' } }) {
                return PayeeKind.PERSON
            }
            return PayeeKind.UNKNOWN
        }
        // No name — a bare phone-number VPA (10+ digits) is an individual on UPI.
        val local = payeeVpa?.substringBefore('@')?.trim()
        if (!local.isNullOrEmpty()) {
            val digits = local.count { it.isDigit() }
            if (digits >= 10 && local.all { it.isDigit() || it == '+' }) return PayeeKind.PERSON
        }
        return PayeeKind.UNKNOWN
    }

    /** True only when we're reasonably sure the payee is an individual (drives the person glyph + the
     *  "don't auto-flag personal payments" rule). UNKNOWN is treated as business-like (stays flaggable). */
    fun isPerson(payeeName: String?, payeeVpa: String? = null): Boolean =
        kind(payeeName, payeeVpa) == PayeeKind.PERSON
}
