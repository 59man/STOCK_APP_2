package com.stocktracker.core.network

import com.stocktracker.core.model.DividendEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@Serializable
private data class YahooDivChartResponse(val chart: YahooDivChart? = null)
@Serializable
private data class YahooDivChart(val result: List<YahooDivResult>? = null)
@Serializable
private data class YahooDivResult(
    val meta: YahooDivMeta? = null,
    val timestamp: List<Long>? = null,
    val events: YahooDivEvents? = null,
)
@Serializable
private data class YahooDivMeta(val currency: String? = null)
@Serializable
private data class YahooDivEvents(val dividends: Map<String, YahooDivRaw>? = null)
@Serializable
private data class YahooDivRaw(val date: Long, val amount: Double)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** Some tickers were renamed; Yahoo keeps dividend history under one symbol only. Mirrors DIVIDEND_TICKER_ALIASES in dividends.ts. */
private val DIVIDEND_TICKER_ALIASES = mapOf("CZG.PR" to "COLT.PR")

/**
 * Dividend events Yahoo no longer has (lost when CZG.PR was delisted and
 * renamed to COLT.PR). Mirrors STATIC_DIVIDENDS in dividends.ts — currency is
 * hardcoded here (CZK, a Prague Exchange ticker) rather than left nullable
 * like the TS `currency?: string`, since [DividendEvent.currency] is
 * non-optional in this port.
 */
private val STATIC_DIVIDENDS: Map<String, List<DividendEvent>> = mapOf(
    "COLT.PR" to listOf(
        DividendEvent("2021-06-25", 7.5, "CZK"),
        DividendEvent("2022-06-01", 25.0, "CZK"),
        DividendEvent("2023-06-16", 30.0, "CZK"),
        DividendEvent("2024-07-03", 30.0, "CZK"),
        DividendEvent("2025-07-03", 15.0, "CZK"),
    ),
)

/**
 * Seam over [DividendClient] so repositories can be unit-tested without a
 * network. Production always binds [DividendClient] (see DataModule).
 */
fun interface DividendSource {
    suspend fun fetchDividendEvents(ticker: String): List<DividendEvent>
}

/** Direct-to-Yahoo dividend event history — ported from src/utils/dividends.ts's fetchDividendEvents. */
object DividendClient : DividendSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchDividendEvents(ticker: String): List<DividendEvent> = withContext(Dispatchers.IO) {
        val lookupTicker = (DIVIDEND_TICKER_ALIASES[ticker.uppercase()] ?: ticker).uppercase()
        val statics = STATIC_DIVIDENDS[lookupTicker] ?: emptyList()

        var payload = fetchChart(lookupTicker, "1wk")
        // Yahoo emits at most one dividend per bar, so an instrument distributing at least
        // as often as the bar interval silently loses events — one event per bar is that
        // signature. Retry at daily resolution, which no real distribution schedule
        // saturates. (Weekly-distribution ETFs like QDTE/XDTE are the case this catches.)
        if (payload.bars > 0 && payload.events.size >= payload.bars) {
            payload = fetchChart(lookupTicker, "1d")
        }

        mergeStatics(payload.events, statics)
    }

    private fun fetchChart(lookupTicker: String, interval: String): DividendChart {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(lookupTicker, "UTF-8")}" +
            "?${yahooDividendQuery(interval)}"
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            // Throw rather than fall through with an empty list: DividendRepository caches
            // whatever this returns for the rest of the process, so swallowing a 429/500
            // would silently pin the ticker at "no dividends" with no retry. Only errors
            // are uncached; a genuinely empty but successful response (an accumulating
            // ETF) still caches, as it should.
            if (!response.isSuccessful) throw Exception("Yahoo dividends ${response.code}")
            val body = response.body?.string() ?: throw Exception("Yahoo dividends: empty body")
            return parseDividendChart(body)
        }
    }
}

/** [bars] is the returned bar count — one dividend per bar means the interval is saturating. */
internal data class DividendChart(val bars: Int, val events: List<DividendEvent>)

/** Yahoo's dividend map → events in the feed's own currency. Exposed for tests. */
internal fun parseDividendChart(body: String): DividendChart {
    val result = PersistJson.decodeFromString(YahooDivChartResponse.serializer(), body).chart?.result?.firstOrNull()
    val currency = result?.meta?.currency ?: "USD"
    val events = (result?.events?.dividends ?: emptyMap()).values.map { d ->
        val date = Instant.ofEpochSecond(d.date).atZone(ZoneOffset.UTC).toLocalDate().toString()
        DividendEvent(date = date, amount = d.amount, currency = currency)
    }
    // Yahoo reports LSE dividends in pence
    val normalised =
        if (currency == "GBp") events.map { it.copy(amount = it.amount / 100, currency = "GBP") } else events
    return DividendChart(bars = result?.timestamp?.size ?: 0, events = normalised)
}

/** Static events fill only ex-dates the live feed doesn't carry — a live event always wins its date. */
internal fun mergeStatics(fetched: List<DividendEvent>, statics: List<DividendEvent>): List<DividendEvent> {
    val fetchedDates = fetched.map { it.date }.toSet()
    return (fetched + statics.filterNot { it.date in fetchedDates }).sortedBy { it.date }
}

/** Static COLT.PR history, for tests that need the same list DividendClient merges. */
internal fun staticDividendsFor(ticker: String): List<DividendEvent> =
    STATIC_DIVIDENDS[(DIVIDEND_TICKER_ALIASES[ticker.uppercase()] ?: ticker).uppercase()] ?: emptyList()
