package com.stocktracker.core.network

import com.stocktracker.core.model.FX_CONVERTED_TICKERS
import com.stocktracker.core.model.PriceHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
private data class YahooHistChartResponse(val chart: YahooHistChart? = null)
@Serializable
private data class YahooHistChart(val result: List<YahooHistResult>? = null)
@Serializable
private data class YahooHistResult(
    val meta: YahooHistMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooHistIndicators? = null,
)
@Serializable
private data class YahooHistMeta(val currency: String? = null)
@Serializable
private data class YahooHistIndicators(val quote: List<YahooHistQuote>? = null)
@Serializable
private data class YahooHistQuote(val close: List<Double?>? = null)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** [currency] is null when the source's own meta didn't carry one (every fx-converted series is pre-converted to CZK instead). */
data class TickerHistoryResult(val points: PriceHistory, val currency: String?)

/**
 * Daily close history, direct from Yahoo — ported from PriceChart.tsx's
 * fetchHistory and PortfolioPnLChart.tsx's fetchYahooHistory/fetchFxHistory.
 * Never routed through the user's own server (Mobile Sync Blueprint, Phase 2 §00).
 */
object HistoryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    private suspend fun fetchRaw(ticker: String, query: String): YahooHistResult? = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${encode(ticker)}?${query}"
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Yahoo history ${response.code}")
            val body = response.body?.string() ?: throw Exception("Yahoo history: empty body")
            PersistJson.decodeFromString(YahooHistChartResponse.serializer(), body).chart?.result?.firstOrNull()
        }
    }

    private fun encode(ticker: String): String = java.net.URLEncoder.encode(ticker, "UTF-8")

    private fun parse(result: YahooHistResult?): PriceHistory {
        val ts = result?.timestamp ?: return emptyList()
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        return ts.indices.mapNotNull { i ->
            val price = closes.getOrNull(i) ?: return@mapNotNull null
            if (!price.isFinite() || price <= 0) return@mapNotNull null
            val date = Instant.ofEpochSecond(ts[i]).atZone(ZoneOffset.UTC).toLocalDate().toString()
            date to price
        }.sortedBy { it.first }
    }

    /**
     * Last point at-or-before [date], falling back to the first point after
     * when that's actually closer — a round-the-clock feed's live/final bar
     * can be timestamped just past UTC midnight, landing one calendar day
     * later than a same-session bar it needs to line up with. Mirrors
     * core:calc's priceAt (kept separate: this merge runs once per fetch on
     * the I/O side, the calc one is queried repeatedly while walking dates).
     */
    private fun priceAtStep(history: PriceHistory, date: String): Double? {
        var lo = 0
        var hi = history.size - 1
        var beforeIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (history[mid].first <= date) { beforeIdx = mid; lo = mid + 1 } else hi = mid - 1
        }
        val before = history.getOrNull(beforeIdx)
        val after = history.getOrNull(lo)
        if (before == null) return after?.second
        if (after == null) return before.second
        val targetDay = LocalDate.parse(date).toEpochDay()
        val beforeGapDays = targetDay - LocalDate.parse(before.first).toEpochDay()
        val afterGapDays = LocalDate.parse(after.first).toEpochDay() - targetDay
        return if (afterGapDays <= 1 && afterGapDays <= beforeGapDays) after.second else before.second
    }

    private fun mergeFx(pricePts: PriceHistory, fxPts: PriceHistory): PriceHistory =
        pricePts.mapNotNull { (date, price) -> priceAtStep(fxPts, date)?.let { rate -> date to price * rate } }

    /** One ticker's daily close history for [yahooRange] (e.g. "1mo".."max"); fx-converted tickers come back pre-multiplied to CZK. */
    suspend fun fetchHistory(ticker: String, yahooRange: String): TickerHistoryResult = coroutineScope {
        val fx = FX_CONVERTED_TICKERS[ticker.uppercase()]
        if (fx != null) {
            val priceDeferred = async { fetchRaw(fx.priceTicker, yahooChartQuery(yahooRange)) }
            val fxDeferred = async { fetchRaw(fx.fxTicker, yahooChartQuery(yahooRange)) }
            val points = mergeFx(parse(priceDeferred.await()), parse(fxDeferred.await()))
            return@coroutineScope TickerHistoryResult(points, "CZK")
        }

        val result = fetchRaw(ticker, yahooChartQuery(yahooRange))
        var currency = result?.meta?.currency
        var points = parse(result)
        if (currency == "GBp") { // Yahoo reports LSE prices in pence
            currency = "GBP"
            points = points.map { (d, p) -> d to p / 100 }
        }
        TickerHistoryResult(points, currency)
    }

    /**
     * CUR→CZK daily close history, full history — silently empty on failure so callers
     * fall back to spot conversion.
     *
     * Cached for the process lifetime, mirroring PortfolioPnLChart.tsx's module-level
     * `fxHistCache`. The series is range-independent by construction, but
     * PortfolioChartViewModel re-runs its fetch on every chart range change, and a daily
     * epoch-to-now FX history is ~600 KB per currency — without this, tapping
     * 1M → 3M → 6M → All would re-download several MB of identical data. Only successful
     * fetches are cached, so a transient failure still retries.
     */
    private val fxHistoryCache = ConcurrentHashMap<String, PriceHistory>()

    suspend fun fetchFxHistory(currency: String): PriceHistory {
        fxHistoryCache[currency]?.let { return it }
        val points = runCatching { parse(fetchRaw("${currency}CZK=X", yahooFxHistoryQuery())) }
            .getOrDefault(emptyList())
        if (points.isNotEmpty()) fxHistoryCache[currency] = points
        return points
    }
}
