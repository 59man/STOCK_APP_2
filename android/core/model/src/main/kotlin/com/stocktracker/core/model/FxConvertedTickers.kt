package com.stocktracker.core.model

/**
 * Tickers whose price must be fetched in a foreign currency and multiplied by
 * the CZK FX rate. Ported from src/data/fxConvertedTickers.ts, whose values
 * are pre-URL-encoded because the web client interpolates them straight into
 * a fetch URL with no further encoding step. The Android network clients
 * (QuoteClient, HistoryClient) always run their own `URLEncoder.encode` on
 * every ticker they fetch, so these must stay **raw** here — pre-encoding
 * them too double-encodes the `=` (`%3D` → `%253D`) and 404s. Add new assets
 * to fxConvertedTickers.ts conceptually and mirror the entry here, raw.
 */
data class FxEntry(
    /** Yahoo Finance price ticker, e.g. "GC=F" — raw, not URL-encoded. */
    val priceTicker: String,
    /** Yahoo Finance FX pair that returns CZK per 1 unit of foreign currency, e.g. "USDCZK=X" — raw. */
    val fxTicker: String,
    /** Displayed when Yahoo name lookup fails. */
    val fallbackName: String? = null,
)

val FX_CONVERTED_TICKERS: Map<String, FxEntry> = mapOf(
    "XAU" to FxEntry(priceTicker = "GC=F", fxTicker = "USDCZK=X", fallbackName = "Gold (XAU)"),
    "4GLD.DE" to FxEntry(priceTicker = "4GLD.DE", fxTicker = "EURCZK=X", fallbackName = "Xetra-Gold"),
    "EXUS.DE" to FxEntry(priceTicker = "EXUS.DE", fxTicker = "EURCZK=X", fallbackName = "iShares MSCI World ex USA"),
)

val FX_CONVERTED_SET: Set<String> = FX_CONVERTED_TICKERS.keys
