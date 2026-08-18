package com.stocktracker.core.calc

/** How many CZK equal 1 unit of the given currency. Mirrors DEFAULTS in src/hooks/useFxRates.ts. */
val DEFAULT_RATES: Map<String, Double> = mapOf(
    "CZK" to 1.0, "USD" to 25.0, "EUR" to 27.5, "GBP" to 29.0,
    "CHF" to 28.0, "JPY" to 0.16, "CAD" to 18.0, "AUD" to 16.0,
)

// Currencies convert() was asked about but doesn't know — warn once each, matching the web client.
private val warnedUnknown = mutableSetOf<String>()

/**
 * Mirrors `convert()` in src/hooks/useFxRates.ts exactly: same-currency and
 * non-finite short-circuits, cross-rate always goes through the rates map
 * (CZK-based), unknown currencies fall back to 1.0 (treated as CZK) with a
 * once-per-currency warning rather than throwing.
 */
fun convert(amount: Double, from: String, to: String, rates: Map<String, Double> = DEFAULT_RATES): Double {
    if (from == to || !amount.isFinite()) return amount

    fun rateOf(currency: String): Double {
        val r = rates[currency]
        if (r == null && warnedUnknown.add(currency)) {
            System.err.println("[fx] unknown currency \"$currency\" — treating as CZK, amounts will be wrong")
        }
        return r ?: 1.0
    }

    return (amount * rateOf(from)) / rateOf(to)
}
