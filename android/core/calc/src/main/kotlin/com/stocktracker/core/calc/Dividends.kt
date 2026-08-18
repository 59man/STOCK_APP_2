package com.stocktracker.core.calc

import com.stocktracker.core.model.DivTaxOverrides
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position

/** Ported verbatim from src/utils/dividends.ts — do not reorder or "correct" rates without checking the source. */
val COUNTRY_WITHHOLDING_RATES: Map<String, Double> = mapOf(
    "CZ" to 0.15, "AT" to 0.275, "BE" to 0.30, "DE" to 0.2637, "DK" to 0.27,
    "ES" to 0.19, "FI" to 0.20, "FR" to 0.128, "HU" to 0.00, "IE" to 0.00,
    "IT" to 0.26, "LU" to 0.00, "NL" to 0.15, "NO" to 0.15, "PL" to 0.19,
    "PT" to 0.25, "SE" to 0.30, "SI" to 0.15, "SK" to 0.15, "CH" to 0.35,
    "GB" to 0.00, "US" to 0.15, "JP" to 0.15315,
)
private const val DEFAULT_COUNTRY = "CZ"
private val DEFAULT_RATE = COUNTRY_WITHHOLDING_RATES.getValue(DEFAULT_COUNTRY)

/** Ported verbatim from src/utils/dividends.ts. */
val TICKER_COUNTRY: Map<String, String> = mapOf(
    "VIG.PR" to "AT", "EXUS.DE" to "IE", "4GLD.DE" to "DE", "UCG.MI" to "IT",
    "DTE.DE" to "DE", "8306.T" to "JP", "8591.T" to "JP", "CSG.AS" to "NL",
)

fun getDividendTaxRate(ticker: String): Double {
    val country = TICKER_COUNTRY[ticker.uppercase()] ?: return DEFAULT_RATE
    return COUNTRY_WITHHOLDING_RATES[country] ?: DEFAULT_RATE
}

/**
 * Net dividend income for one ticker's lots. Mirrors calcNetDividends in
 * src/utils/dividends.ts: a lot only receives a dividend if it was bought
 * on/before the ex-date and, if sold, sold strictly *after* the ex-date —
 * a lot sold exactly on the ex-date does not receive that dividend.
 */
fun calcNetDividends(
    lots: List<Position>,
    dividends: List<DividendEvent>,
    ticker: String,
    taxOverrides: DivTaxOverrides = emptyMap(),
): Double {
    var total = 0.0
    for (div in dividends) {
        val rate = taxOverrides["${ticker.uppercase()}::${div.date}"] ?: getDividendTaxRate(ticker)
        val shares = lots
            .filter { lot ->
                val sellDate = lot.sellDate
                lot.buyDate <= div.date && (sellDate == null || sellDate > div.date)
            }
            .sumOf { it.quantity }
        total += shares * div.amount * (1 - rate)
    }
    return total
}
