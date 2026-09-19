package com.stocktracker.core.network

import com.stocktracker.core.calc.AnchorBar
import com.stocktracker.core.calc.needsIntraday
import com.stocktracker.core.calc.resolveAnchorFromDaily
import com.stocktracker.core.calc.resolveAnchorFromIntraday
import com.stocktracker.core.model.FX_CONVERTED_TICKERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
private data class AnchorChartResponse(val chart: AnchorChart? = null)
@Serializable
private data class AnchorChart(val result: List<AnchorResult>? = null)
@Serializable
private data class AnchorResult(
    val meta: AnchorMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: AnchorIndicators? = null,
)
@Serializable
private data class AnchorMeta(
    val regularMarketTime: Long? = null,
    val regularMarketPrice: Double? = null,
    val currentTradingPeriod: AnchorPeriods? = null,
)
@Serializable
private data class AnchorPeriods(val regular: AnchorWindow? = null)
@Serializable
private data class AnchorWindow(val start: Long? = null, val end: Long? = null)
@Serializable
private data class AnchorIndicators(val quote: List<AnchorQuote>? = null)
@Serializable
private data class AnchorQuote(val close: List<Double?>? = null)

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/** [price] is null when no anchor could be resolved; the row then falls back and says so. */
data class ResolvedAnchor(val price: Double?, val lastTradedAt: Long?)

/**
 * The price each ticker was worth at the user's local midnight.
 *
 * Mirrors the web's useDailyAnchors. The cheap path matters most: when nothing has traded
 * since local midnight — every weekend, and every weekday before the open — the anchor is the
 * current price and one small request settles it.
 */
object AnchorClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(ticker: String, midnight: Long): ResolvedAnchor = coroutineScope {
        val key = ticker.uppercase()

        // FX-converted tickers have no single Yahoo symbol: their price is a foreign quote
        // times an FX pair, so the anchor is the product of the two anchors. expandFx = false
        // on the recursion because 4GLD.DE and EXUS.DE name themselves as their own price
        // ticker, which would otherwise never terminate.
        val fx = FX_CONVERTED_TICKERS[key]
        if (fx != null) {
            val price = async { resolveRaw(fx.priceTicker, midnight) }
            val rate = async { resolveRaw(fx.fxTicker, midnight) }
            val p = price.await()
            val r = rate.await()
            return@coroutineScope if (p.price == null || r.price == null) {
                ResolvedAnchor(null, p.lastTradedAt)
            } else {
                ResolvedAnchor(p.price * r.price, p.lastTradedAt)
            }
        }
        resolveRaw(ticker, midnight)
    }

    private suspend fun resolveRaw(ticker: String, midnight: Long): ResolvedAnchor {
        val daily = fetch(ticker, "interval=1d&range=5d") ?: return ResolvedAnchor(null, null)
        val meta = daily.meta
        val lastTradedAt = meta?.regularMarketTime

        if (lastTradedAt != null && lastTradedAt <= midnight) {
            // Nothing has traded today, so the anchor is where we already are.
            return ResolvedAnchor(meta.regularMarketPrice, lastTradedAt)
        }

        val regular = meta?.currentTradingPeriod?.regular
        if (!needsIntraday(regular?.start, regular?.end, midnight)) {
            val sessionLength = if (regular?.start != null && regular.end != null) {
                regular.end - regular.start
            } else {
                0L
            }
            resolveAnchorFromDaily(barsOf(daily), midnight, sessionLength)
                ?.let { return ResolvedAnchor(it, lastTradedAt) }
        }

        val intraday = fetch(ticker, "interval=5m&range=2d")
        return ResolvedAnchor(resolveAnchorFromIntraday(barsOf(intraday), midnight), lastTradedAt)
    }

    /** Null when the currency pair could not be resolved; the caller then holds FX flat. */
    suspend fun resolveFx(from: String, to: String, midnight: Long): Double? {
        if (from.equals(to, ignoreCase = true)) return 1.0
        val pair = "$from$to=X"
        resolveAnchorFromIntraday(barsOf(fetch(pair, "interval=5m&range=2d")), midnight)?.let { return it }
        // FX closes over the weekend, so the 2-day intraday window can be empty on a Saturday
        // while daily bars still carry Friday's rate — which is the correct anchor.
        return resolveAnchorFromDaily(barsOf(fetch(pair, "interval=1d&range=5d")), midnight, 0)
    }

    private fun barsOf(result: AnchorResult?): List<AnchorBar> {
        val stamps = result?.timestamp ?: return emptyList()
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return emptyList()
        return stamps.indices.mapNotNull { i ->
            val close = closes.getOrNull(i)
            if (close != null && close.isFinite()) AnchorBar(stamps[i], close) else null
        }
    }

    private suspend fun fetch(ticker: String, query: String): AnchorResult? = withContext(Dispatchers.IO) {
        // Encoded exactly once — see the FX_CONVERTED_TICKERS gotcha in CLAUDE.md.
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
            "${URLEncoder.encode(ticker, "UTF-8")}?$query"
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                PersistJson.decodeFromString(AnchorChartResponse.serializer(), body)
                    .chart?.result?.firstOrNull()
            }
        }.getOrNull()
    }
}
