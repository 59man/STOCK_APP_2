package com.stocktracker.core.data

import com.stocktracker.core.model.NO_FEED_TICKERS
import com.stocktracker.core.model.Quote
import com.stocktracker.core.network.QuoteClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        candidates.forEach { ticker ->
            launch {
                // add/remove of `ticker` both happen inside this single launch body, so a
                // ticker can never be marked in-flight without a job guaranteed to clear it —
                // even if this coroutineScope's parent is cancelled mid-loop (e.g. active
                // portfolio switched again), a not-yet-started launch never runs its body at
                // all, so it never adds itself in the first place.
                val started = inFlightLock.withLock {
                    if (ticker in inFlight) false else { inFlight.add(ticker); true }
                }
                if (!started) return@launch

                _loading.update { it + ticker }
                try {
                    val quote = fetchOne(ticker)
                    _quotes.update { it + (ticker to quote) }
                    _errors.update { it - ticker }
                } catch (e: CancellationException) {
                    throw e // cancellation is not a fetch failure — don't surface it as a quote error
                } catch (e: Exception) {
                    _errors.update { it + (ticker to (e.message ?: "Failed")) }
                } finally {
                    // NonCancellable: withLock is itself suspending, so under cancellation it
                    // would throw immediately and skip the removal, recreating the same leak.
                    withContext(NonCancellable) {
                        inFlightLock.withLock { inFlight.remove(ticker) }
                    }
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
        } catch (e: CancellationException) {
            // Ahead of the generic catch below, which would otherwise turn a cancelled
            // fetch into a stale-cache "success" — the caller would then go on to publish
            // that quote and clear the ticker's error entry from a job that is already dead.
            throw e
        } catch (e: Exception) {
            cache[ticker]?.quote ?: throw e // stale beats nothing
        }
    }
}
