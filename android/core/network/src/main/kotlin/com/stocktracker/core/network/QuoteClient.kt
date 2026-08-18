package com.stocktracker.core.network

import com.stocktracker.core.model.FX_CONVERTED_TICKERS
import com.stocktracker.core.model.FX_CONVERTED_SET
import com.stocktracker.core.model.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Serializable
private data class YahooChartResponse(val chart: YahooChart? = null)
@Serializable
private data class YahooChart(val result: List<YahooChartResult>? = null)
@Serializable
private data class YahooChartResult(
    val meta: YahooMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooIndicators? = null,
)
@Serializable
private data class YahooMeta(
    val currency: String? = null,
    val regularMarketPrice: Double? = null,
    val previousClose: Double? = null,
    val chartPreviousClose: Double? = null,
    val shortName: String? = null,
    val longName: String? = null,
    val gmtoffset: Long? = null,
)
@Serializable
private data class YahooIndicators(val quote: List<YahooQuoteIndicator>? = null)
@Serializable
private data class YahooQuoteIndicator(val close: List<Double?>? = null)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class YahooRateLimitedException(message: String) : Exception(message)

/**
 * Direct-to-Yahoo/Stooq quote client — the phone never routes this through
 * the user's own server (see the Mobile Sync Blueprint, Phase 2 §00). Yahoo
 * blocks requests that don't look like a browser, hence the spoofed
 * User-Agent, mirroring what the server's own proxyRequest() already does.
 * Ported from src/hooks/useQuotes.ts's module-level fetch functions.
 */
object QuoteClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    private val cooldownUntilMs = AtomicLong(0)
    private const val YAHOO_COOLDOWN_MS = 120_000L

    private fun checkCooldown() {
        if (System.currentTimeMillis() < cooldownUntilMs.get()) {
            throw YahooRateLimitedException("Yahoo rate-limited (429) — retry later")
        }
    }

    private fun noteYahoo429(): Nothing {
        cooldownUntilMs.set(System.currentTimeMillis() + YAHOO_COOLDOWN_MS)
        throw YahooRateLimitedException("Yahoo rate-limited (429) — retry later")
    }

    private suspend fun fetchYahooChart(ticker: String, range: String): Pair<Int, YahooChartResponse?> =
        withContext(Dispatchers.IO) {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/${encode(ticker)}?interval=1d&range=$range"
            val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).header("Accept", "application/json, text/plain, */*").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                val parsed = body?.let { runCatching { PersistJson.decodeFromString(YahooChartResponse.serializer(), it) }.getOrNull() }
                response.code to parsed
            }
        }

    private fun encode(ticker: String): String = java.net.URLEncoder.encode(ticker, "UTF-8")

    /** Last completed daily close strictly before the day of the latest bar — see useQuotes.ts's prevDailyClose for why. */
    private fun prevDailyClose(result: YahooChartResult?): Double? {
        val ts = result?.timestamp ?: return null
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return null
        val offsetSeconds = result.meta?.gmtoffset ?: 0L
        val valid = ts.indices.mapNotNull { i ->
            val c = closes.getOrNull(i) ?: return@mapNotNull null
            if (!c.isFinite()) return@mapNotNull null
            val day = Instant.ofEpochSecond(ts[i] + offsetSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString()
            day to c
        }
        if (valid.isEmpty()) return null
        val lastDay = valid.last().first
        for (i in valid.indices.reversed()) {
            if (valid[i].first != lastDay) return valid[i].second
        }
        return null
    }

    private suspend fun fetchFromYahooProxy(ticker: String): Quote {
        checkCooldown()
        val (code, parsed) = withTimeout(9_000) { fetchYahooChart(ticker, "1d") }
        if (code == 429) noteYahoo429()
        if (code !in 200..299) throw Exception("Yahoo $code")
        val meta = parsed?.chart?.result?.firstOrNull()?.meta
        val price = meta?.regularMarketPrice ?: throw Exception("Yahoo: no data")
        val prev = meta.previousClose ?: meta.chartPreviousClose ?: price
        var currency = meta.currency ?: "CZK"
        var finalPrice = price
        var change = price - prev
        if (currency == "GBp") { // Yahoo reports LSE prices in pence
            currency = "GBP"
            finalPrice /= 100
            change /= 100
        }
        val prevForPercent = if (currency == "GBP" && meta.currency == "GBp") prev / 100 else prev
        return Quote(
            ticker = ticker.uppercase(), price = finalPrice, change = change,
            changePercent = if (prevForPercent > 0) (change / prevForPercent) * 100 else 0.0,
            currency = currency, name = meta.shortName ?: meta.longName ?: ticker,
            lastUpdated = Instant.now().toString(),
        )
    }

    private suspend fun fetchFromStooq(ticker: String): Quote = withContext(Dispatchers.IO) {
        val url = "https://stooq.com/q/l/?s=${ticker.lowercase()}&f=sd2t2ohlcv&h&e=csv"
        val request = Request.Builder().url(url).build()
        val text = withTimeout(9_000) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Stooq ${response.code}")
                response.body?.string() ?: throw Exception("Stooq: empty body")
            }
        }
        val lines = text.trim().split("\n")
        if (lines.size < 2 || lines[1].trim() == "N/D") throw Exception("Stooq: no data")
        val cols = lines[1].split(",")
        val date = cols.getOrNull(1)
        val open = cols.getOrNull(3)?.toDoubleOrNull()
        val close = cols.getOrNull(6)?.toDoubleOrNull()
        if (close == null || close == 0.0) throw Exception("Stooq: invalid price")
        val suffix = ticker.substringAfterLast('.', "").uppercase()
        val currency = when {
            suffix == "PR" || suffix == "CZ" -> "CZK"
            suffix in setOf("VI", "AS", "DE") -> "EUR"
            suffix == "T" -> "JPY"
            else -> "USD"
        }
        Quote(
            ticker = ticker.uppercase(), price = close, change = close - (open ?: close),
            changePercent = if (open != null && open > 0) ((close - open) / open) * 100 else 0.0,
            currency = currency, name = ticker.uppercase(), lastUpdated = date ?: Instant.now().toString(),
        )
    }

    private suspend fun fetchFxConvertedQuote(ticker: String): Quote = coroutineScope {
        checkCooldown()
        val entry = FX_CONVERTED_TICKERS.getValue(ticker.uppercase())
        val priceDeferred = async { fetchYahooChart(entry.priceTicker, "5d") }
        val fxDeferred = async { fetchYahooChart(entry.fxTicker, "5d") }
        val (priceCode, priceResp) = withTimeout(9_000) { priceDeferred.await() }
        val (fxCode, fxResp) = withTimeout(9_000) { fxDeferred.await() }
        if (priceCode == 429 || fxCode == 429) noteYahoo429()
        if (priceCode !in 200..299) throw Exception("Price fetch $priceCode")
        if (fxCode !in 200..299) throw Exception("FX fetch $fxCode")

        val priceResult = priceResp?.chart?.result?.firstOrNull()
        val fxResult = fxResp?.chart?.result?.firstOrNull()
        val pm = priceResult?.meta
        val fm = fxResult?.meta
        val pPrice = pm?.regularMarketPrice
        val fPrice = fm?.regularMarketPrice
        if (pPrice == null || fPrice == null) throw Exception("No price data")

        val price = pPrice * fPrice
        val prevPriceClose = prevDailyClose(priceResult) ?: pm.previousClose ?: pPrice
        val prevFxClose = prevDailyClose(fxResult) ?: fm.previousClose ?: fPrice
        val prevPrice = prevPriceClose * prevFxClose

        Quote(
            ticker = ticker.uppercase(), price = price, change = price - prevPrice,
            changePercent = if (prevPrice > 0) ((price - prevPrice) / prevPrice) * 100 else 0.0,
            currency = "CZK", name = pm.shortName ?: pm.longName ?: entry.fallbackName ?: ticker.uppercase(),
            lastUpdated = Instant.now().toString(),
        )
    }

    /** One ticker, trying Yahoo then Stooq (or the FX-converted path), no caching — caching is the repository's job. */
    suspend fun fetchQuote(ticker: String): Quote {
        val key = ticker.uppercase()
        if (FX_CONVERTED_SET.contains(key)) return fetchFxConvertedQuote(ticker)

        var lastError: Exception = Exception("All sources failed")
        for (source in listOf<suspend (String) -> Quote>(::fetchFromYahooProxy, ::fetchFromStooq)) {
            try {
                return source(ticker)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }
}
