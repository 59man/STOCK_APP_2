package com.stocktracker.core.calc

import com.stocktracker.core.model.FUND_PROVIDER_TICKERS
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Quote

/**
 * Live fund prices worth storing as the manual fallback: a fund has no Yahoo history to fall back on,
 * so if its provider is unreachable the row shows whatever manual price is stored, and that should be
 * the last real live price. Mirrors the second effect in PortfolioContent.tsx.
 */
fun fundPricesToStore(
    tickers: Collection<String>,
    quotes: Map<String, Quote>,
    manualPrices: Map<String, ManualPriceEntry>,
): Map<String, Double> = tickers.map { it.uppercase() }.distinct()
    .filter { it in FUND_PROVIDER_TICKERS }
    .mapNotNull { ticker ->
        val price = quotes[ticker]?.price?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
        if (manualPrices[ticker]?.price == price) null else ticker to price
    }
    .toMap()
