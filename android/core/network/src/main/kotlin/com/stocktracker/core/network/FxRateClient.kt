package com.stocktracker.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
private data class YahooFxChartResponse(val chart: YahooFxChart? = null)
@Serializable
private data class YahooFxChart(val result: List<YahooFxResult>? = null)
@Serializable
private data class YahooFxResult(val meta: YahooFxMeta? = null)
@Serializable
private data class YahooFxMeta(val regularMarketPrice: Double? = null)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** One CZK-quoted FX pair fetch, direct from Yahoo — ported from src/hooks/useFxRates.ts's fetchFxRate. */
object FxRateClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    /** [fxTicker] e.g. "USDCZK=X" — how many CZK equal 1 unit of the pair's base currency. */
    suspend fun fetchRate(fxTicker: String): Double = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${java.net.URLEncoder.encode(fxTicker, "UTF-8")}?interval=1d&range=1d"
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("FX ${response.code}")
            val body = response.body?.string() ?: throw Exception("FX: empty body")
            val parsed = PersistJson.decodeFromString(YahooFxChartResponse.serializer(), body)
            val price = parsed.chart?.result?.firstOrNull()?.meta?.regularMarketPrice
            if (price == null || !price.isFinite()) throw Exception("No FX data")
            price
        }
    }
}
