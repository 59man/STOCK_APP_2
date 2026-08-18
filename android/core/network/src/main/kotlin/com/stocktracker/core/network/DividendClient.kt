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
private data class YahooDivResult(val meta: YahooDivMeta? = null, val events: YahooDivEvents? = null)
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

/** Direct-to-Yahoo dividend event history — ported from src/utils/dividends.ts's fetchDividendEvents. */
object DividendClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun fetchDividendEvents(ticker: String): List<DividendEvent> = withContext(Dispatchers.IO) {
        val lookupTicker = (DIVIDEND_TICKER_ALIASES[ticker.uppercase()] ?: ticker).uppercase()
        val statics = STATIC_DIVIDENDS[lookupTicker] ?: emptyList()

        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(lookupTicker, "UTF-8")}" +
            "?range=max&interval=1d&events=div"
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()

        var fetched: List<DividendEvent> = emptyList()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                val parsed = body?.let { runCatching { PersistJson.decodeFromString(YahooDivChartResponse.serializer(), it) }.getOrNull() }
                val result = parsed?.chart?.result?.firstOrNull()
                var currency = result?.meta?.currency ?: "USD"
                val raw = result?.events?.dividends ?: emptyMap()
                fetched = raw.values.map { d ->
                    val date = Instant.ofEpochSecond(d.date).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    DividendEvent(date = date, amount = d.amount, currency = currency)
                }
                if (currency == "GBp") { // Yahoo reports LSE dividends in pence
                    fetched = fetched.map { it.copy(amount = it.amount / 100, currency = "GBP") }
                }
            }
        }

        val fetchedDates = fetched.map { it.date }.toSet()
        (fetched + statics.filterNot { it.date in fetchedDates }).sortedBy { it.date }
    }
}
