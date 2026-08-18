package com.stocktracker.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Storage key scheme — must match server/data.json exactly. See CLAUDE.md "Storage key schema". */
object PersistKeys {
    fun positions(portfolioId: String) = "stock_tracker_positions_$portfolioId"
    fun manualPrices(portfolioId: String) = "stock_tracker_manual_prices_$portfolioId"
    fun divTaxOverrides(portfolioId: String) = "stock_tracker_div_tax_$portfolioId"
    const val PORTFOLIOS = "stock_tracker_portfolios"
    const val ACTIVE_PORTFOLIO = "stock_tracker_active_portfolio"
}

@OptIn(ExperimentalSerializationApi::class)
val PersistJson: Json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * The persist protocol double-encodes: the server's `value` field is itself a
 * JSON string that must be decoded again to get the real payload. Skipping
 * this second decode is the single most likely implementation bug — see the
 * Mobile Sync Blueprint, Phase 2 §02. These two helpers are the only place
 * that should ever touch [PersistApi.get]/[PersistApi.set] directly.
 */
suspend inline fun <reified T> PersistApi.getDecoded(key: String): T? {
    val raw = get(key).value ?: return null
    return PersistJson.decodeFromString<T>(raw)
}

suspend inline fun <reified T> PersistApi.setEncoded(key: String, value: T) {
    set(key, PersistBody(PersistJson.encodeToString(value)))
}
