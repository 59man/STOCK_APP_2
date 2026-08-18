package com.stocktracker.core.data

import com.stocktracker.core.model.NO_FEED_TICKERS
import com.stocktracker.core.model.Quote
import com.stocktracker.core.network.QuoteClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private data class CacheEntry(val quote: Quote, val fetchedAtMs: Long)
private const val CACHE_TTL_MS = 60_000L

/**
 * Live quotes, direct from Yahoo/Stooq (see QuoteClient) — never through the
 * user's own server. Mirrors useQuotes.ts: 60 s cache, in-flight dedup per
 * ticker, and a stale cache entry served if every source fails rather than
 * surfacing an error when one was already known. [NO_FEED_TICKERS] are
 * skipped entirely — those are manual-price-only instruments.
 */
@Singleton
class QuoteRepository @Inject constructor() {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlightLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val quotes: StateFlow<Map<String, Quote>> = _quotes

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors

    suspend fun fetchTickers(tickers: List<String>) = coroutineScope {
        val candidates = tickers.map { it.uppercase() }.distinct().filterNot { it in NO_FEED_TICKERS }
        val toFetch = inFlightLock.withLock {
            candidates.filterNot { it in inFlight }.also { inFlight.addAll(it) }
        }
        if (toFetch.isEmpty()) return@coroutineScope

        _loading.update { it + toFetch }
        toFetch.forEach { ticker ->
            launch {
                try {
                    val quote = fetchOne(ticker)
                    _quotes.update { it + (ticker to quote) }
                    _errors.update { it - ticker }
                } catch (e: Exception) {
                    _errors.update { it + (ticker to (e.message ?: "Failed")) }
                } finally {
                    inFlightLock.withLock { inFlight.remove(ticker) }
                    _loading.update { it - ticker }
                }
            }
        }
    }

    private suspend fun fetchOne(ticker: String): Quote {
        val now = System.currentTimeMillis()
        cache[ticker]?.let { entry -> if (now - entry.fetchedAtMs < CACHE_TTL_MS) return entry.quote }

        return try {
            val quote = QuoteClient.fetchQuote(ticker)
            cache[ticker] = CacheEntry(quote, now)
            quote
        } catch (e: Exception) {
            cache[ticker]?.quote ?: throw e // stale beats nothing
        }
    }
}
