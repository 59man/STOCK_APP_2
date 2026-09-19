package com.stocktracker.core.model

/** One session window as epoch seconds, exactly as Yahoo reports it. */
data class TradingPeriod(val startEpoch: Long, val endEpoch: Long)

data class TradingPeriods(
    val pre: TradingPeriod? = null,
    val regular: TradingPeriod? = null,
    val post: TradingPeriod? = null,
)

/**
 * Everything the app knows about an instrument beyond its price.
 *
 * Fields above the divider come from the chart-meta call — the same request the quote fetch
 * already makes, which always works. Fields below come from Yahoo's quoteSummary, which sits
 * behind an undocumented cookie/crumb handshake: they are null whenever that fails, and every
 * consumer hides the row rather than showing a placeholder.
 */
data class InstrumentProfile(
    val ticker: String,
    val longName: String? = null,
    val exchange: String? = null,
    val instrumentType: String? = null,
    val currency: String? = null,
    /** IANA id of the exchange's own zone, e.g. `America/New_York`. */
    val exchangeTimeZone: String? = null,
    val tradingPeriods: TradingPeriods? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val volume: Long? = null,
    val firstTradeDate: Long? = null,
    // ---- best-effort, from quoteSummary ----
    val description: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val website: String? = null,
    val country: String? = null,
    val fundFamily: String? = null,
    val legalType: String? = null,
    val expenseRatio: Double? = null,
    val totalNetAssets: Double? = null,
    val category: String? = null,
    val fetchedAt: Long = 0L,
) {
    /** True when the crumb-gated call contributed nothing, so the About card has no content. */
    val hasProfileDetail: Boolean
        get() = description != null || sector != null || fundFamily != null || legalType != null
}
