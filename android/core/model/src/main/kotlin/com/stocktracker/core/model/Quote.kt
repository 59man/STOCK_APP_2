package com.stocktracker.core.model

import kotlinx.serialization.Serializable

/** Mirrors `Quote` in src/types/index.ts. */
@Serializable
data class Quote(
    val ticker: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val name: String,
    val lastUpdated: String,
)

/** Mirrors the `{ price, updatedAt }` shape stored per ticker in useManualPrices.ts. */
@Serializable
data class ManualPriceEntry(
    val price: Double,
    val updatedAt: String,
)

/** Per-share dividend event, native-currency amount. */
@Serializable
data class DividendEvent(
    val date: String,
    val amount: Double,
    /** Null when the feed names none; every consumer then falls back to the lot's currency, as the web does. */
    val currency: String? = null,
)

/** Mirrors the `Record<"TICKER::YYYY-MM-DD", number>` shape in useManualDividendTaxes.ts. */
typealias DivTaxOverrides = Map<String, Double>
