package com.stocktracker.core.data

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.NO_FEED_TICKERS
import com.stocktracker.core.network.DividendClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live dividend history, direct from Yahoo (see DividendClient) — never
 * through the user's own server. Mirrors useDividends.ts: cached forever on
 * success (errors are not cached, so a failed ticker retries on the next
 * fetch cycle), in-flight dedup per ticker. [NO_FEED_TICKERS] never fetched.
 */
@Singleton
class DividendRepository @Inject constructor() {
    private val inFlightLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _dividends = MutableStateFlow<Map<String, List<DividendEvent>>>(emptyMap())
    val dividends: StateFlow<Map<String, List<DividendEvent>>> = _dividends

    suspend fun fetchTickers(tickers: List<String>) = coroutineScope {
        val cached = _dividends.value.keys
        val candidates = tickers.map { it.uppercase() }.distinct().filterNot { it in NO_FEED_TICKERS }
        val toFetch = inFlightLock.withLock {
            candidates.filterNot { it in cached || it in inFlight }.also { inFlight.addAll(it) }
        }
        if (toFetch.isEmpty()) return@coroutineScope

        toFetch.forEach { ticker ->
            launch {
                try {
                    val events = DividendClient.fetchDividendEvents(ticker)
                    _dividends.update { it + (ticker to events) }
                } catch (_: Exception) {
                    // Don't cache errors — allow retry on the next fetch cycle.
                } finally {
                    inFlightLock.withLock { inFlight.remove(ticker) }
                }
            }
        }
    }
}
