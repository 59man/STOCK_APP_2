package com.stocktracker.core.model

/**
 * Tickers whose price must be fetched in a foreign currency and multiplied by
 * the CZK FX rate. Ported verbatim from src/data/fxConvertedTickers.ts — add
 * new assets there conceptually and mirror the entry here.
 */
data class FxEntry(
    /** Yahoo Finance price ticker, URL-encoded. */
    val priceTicker: String,
    /** Yahoo Finance FX pair that returns CZK per 1 unit of foreign currency. */
    val fxTicker: String,
    /** Displayed when Yahoo name lookup fails. */
    val fallbackName: String? = null,
)

val FX_CONVERTED_TICKERS: Map<String, FxEntry> = mapOf(
    "XAU" to FxEntry(priceTicker = "GC%3DF", fxTicker = "USDCZK%3DX", fallbackName = "Gold (XAU)"),
    "4GLD.DE" to FxEntry(priceTicker = "4GLD.DE", fxTicker = "EURCZK%3DX", fallbackName = "Xetra-Gold"),
    "EXUS.DE" to FxEntry(priceTicker = "EXUS.DE", fxTicker = "EURCZK%3DX", fallbackName = "iShares MSCI World ex USA"),
)

val FX_CONVERTED_SET: Set<String> = FX_CONVERTED_TICKERS.keys
