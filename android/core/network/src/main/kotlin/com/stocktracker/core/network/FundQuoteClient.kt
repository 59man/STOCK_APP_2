package com.stocktracker.core.network

import com.stocktracker.core.model.FundProvider
import com.stocktracker.core.model.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val COOKIE_TTL_MS = 30 * 60_000L

internal data class FundPoint(val price: Double, val prevClose: Double, val date: String)

/** Mirrors parseOnemarketsCsv: `DD.MM.YYYY;price;; DD.MM.YYYY;price;; ...`. */
internal fun parseOnemarketsCsv(csv: String): FundPoint {
    val entries = csv.split(Regex("\\s+")).mapNotNull { chunk ->
        val fields = chunk.split(';')
        val date = fields.getOrNull(0)?.split('.')?.takeIf { it.size == 3 } ?: return@mapNotNull null
        val price = fields.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        "${date[2]}-${date[1]}-${date[0]}" to price
    }
    check(entries.isNotEmpty()) { "onemarkets: no data points" }
    val last = entries.last()
    return FundPoint(last.second, entries.getOrElse(entries.size - 2) { last }.second, last.first)
}

@Serializable
private data class FioPoint(val x: String, val value: Double)

/** Mirrors parseFioFundJson: `[{ x: 'YYYY-MM-DD', value: number }, ...]`. */
internal fun parseFioFundJson(json: String): FundPoint {
    val points = PersistJson.decodeFromString(ListSerializer(FioPoint.serializer()), json)
    check(points.isNotEmpty()) { "fio-fund: no data points" }
    val last = points.last()
    return FundPoint(last.value, points.getOrElse(points.size - 2) { last }.value, last.x)
}

/**
 * Prices the onemarkets and Fio funds directly from each provider's website, as the server's
 * /api/onemarkets and /api/fio-fund routes do for the web app. The phone has no CORS to work around
 * and never routes market data through the user's server (Mobile Sync Blueprint, Phase 2 §00).
 */
class FundQuoteClient(
    private val onemarketsBase: String = "https://www.onemarkets.cz",
    private val fioBase: String = "https://www.fiofondy.cz/cs/podilove-fondy",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    @Volatile private var fioCookie: Pair<String, Long>? = null

    suspend fun fetchQuote(ticker: String, provider: FundProvider, today: LocalDate = LocalDate.now()): Quote {
        val key = ticker.uppercase()
        val point = withContext(Dispatchers.IO) {
            when (provider) {
                FundProvider.Onemarkets -> parseOnemarketsCsv(onemarketsCsv(key, today))
                is FundProvider.FioFund -> parseFioFundJson(fioChartJson(provider.slug))
            }
        }
        val change = point.price - point.prevClose
        return Quote(
            ticker = key,
            price = point.price,
            change = change,
            changePercent = if (point.prevClose > 0) change / point.prevClose * 100 else 0.0,
            currency = "CZK",
            name = key,
            lastUpdated = point.date,
        )
    }

    private fun onemarketsCsv(isin: String, today: LocalDate): String {
        val ymd = DateTimeFormatter.BASIC_ISO_DATE
        val url = "$onemarketsBase/bin/onemarkets-relaunch/multi-chartdata.csv".toHttpUrl().newBuilder()
            .addQueryParameter("isin", isin)
            .addQueryParameter("underlyings", "")
            .addQueryParameter("underlyingsIds", "")
            .addQueryParameter("start", today.minusDays(14).format(ymd)) // comfortably covers weekends and holidays
            .addQueryParameter("end", today.format(ymd))
            .addQueryParameter("extras", "")
            .addQueryParameter("exchange", "")
            .addQueryParameter("tradingStartTime", "00:00")
            .addQueryParameter("tradingEndTime", "23:59")
            .addQueryParameter("underlyingCurrency", "")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "onemarkets ${response.code}" }
            response.body?.string().orEmpty()
        }
    }

    /** The chart is a Nette signal gated by a session cookie, which any fund page hands out. */
    private fun fioSessionCookie(): String {
        fioCookie?.let { (cookie, expiresAt) -> if (System.currentTimeMillis() < expiresAt) return cookie }
        val request = Request.Builder().url("$fioBase/globalni-akciovy-fond").header("User-Agent", BROWSER_USER_AGENT).build()
        val cookie = client.newCall(request).execute().use { response ->
            response.headers("Set-Cookie").joinToString("; ") { it.substringBefore(';') }
        }
        check(cookie.isNotEmpty()) { "fio-fund: no session cookie" }
        fioCookie = cookie to System.currentTimeMillis() + COOKIE_TTL_MS
        return cookie
    }

    private fun fioChartJson(slug: String): String {
        val fundUrl = "$fioBase/$slug"
        val request = Request.Builder().url("$fundUrl?do=getFundChartData")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", fundUrl)
            .header("Cookie", fioSessionCookie())
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
        // A stale session answers 200 with a {"redirect":...} object rather than an array.
        if (body == null || !body.trimStart().startsWith("[")) {
            fioCookie = null
            error("fio-fund: no data")
        }
        return body
    }
}
