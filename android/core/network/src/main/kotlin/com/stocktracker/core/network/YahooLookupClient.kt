package com.stocktracker.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
private data class YahooSearchResponse(val quotes: List<YahooSearchQuote>? = null)

@Serializable
private data class YahooSearchQuote(
    val symbol: String? = null,
    val quoteType: String? = null,
    val longname: String? = null,
    val shortname: String? = null,
)

/** Mirrors QuoteInfo in src/utils/yahooLookup.ts. */
data class QuoteInfo(val ticker: String, val type: String)

/** Ticker + display name — used by the ticker-edit ISIN lookup, which (unlike [QuoteInfo]) also wants a name to fill in. */
data class NamedQuoteInfo(val ticker: String, val name: String?)

private fun mapType(quoteType: String?): String = when (quoteType) {
    "ETF" -> "etf"
    "MUTUALFUND" -> "fund"
    "COMMODITY" -> "commodity"
    else -> "stock"
}

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

/**
 * ISIN/ticker → { ticker, type } resolution via Yahoo Finance search, direct
 * from the phone — never through the user's own server (see the Mobile Sync
 * Blueprint, Phase 2 §00). Ported from src/utils/yahooLookup.ts.
 */
object YahooLookupClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    private suspend fun query(q: String): YahooSearchQuote? = withContext(Dispatchers.IO) {
        try {
            val url = "https://query1.finance.yahoo.com/v1/finance/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&lang=en-US"
            val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val parsed = runCatching { PersistJson.decodeFromString(YahooSearchResponse.serializer(), body) }.getOrNull()
                val quotes = parsed?.quotes ?: emptyList()
                quotes.firstOrNull { it.quoteType != "OPTION" } ?: quotes.firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve an ISIN to { ticker, type }; falls back to the ISIN itself as the ticker on a miss. */
    suspend fun lookupIsin(isin: String): QuoteInfo {
        val hit = query(isin)
        return QuoteInfo(ticker = hit?.symbol ?: isin, type = mapType(hit?.quoteType))
    }

    /** Same lookup as [lookupIsin], but also returns a display name — null on a miss (no fallback ticker). */
    suspend fun lookupIsinWithName(isinOrTicker: String): NamedQuoteInfo? {
        val hit = query(isinOrTicker) ?: return null
        val symbol = hit.symbol ?: return null
        return NamedQuoteInfo(ticker = symbol, name = hit.longname ?: hit.shortname)
    }

    /** Resolve a ticker for type enrichment only — the ticker itself is never changed. */
    suspend fun lookupTicker(ticker: String): QuoteInfo {
        val hit = query(ticker)
        return QuoteInfo(ticker = ticker, type = mapType(hit?.quoteType))
    }

    suspend fun batchIsins(isins: List<String>): Map<String, QuoteInfo> = coroutineScope {
        isins.distinct().map { isin -> isin to async { lookupIsin(isin) } }.associate { (isin, deferred) -> isin to deferred.await() }
    }

    suspend fun batchTickers(tickers: List<String>): Map<String, QuoteInfo> = coroutineScope {
        tickers.distinct().map { t -> t to async { lookupTicker(t) } }.associate { (t, deferred) -> t to deferred.await() }
    }
}
