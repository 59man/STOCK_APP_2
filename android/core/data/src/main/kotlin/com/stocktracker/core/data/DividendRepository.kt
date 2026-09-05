package com.stocktracker.core.data

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.NO_FEED_TICKERS
import com.stocktracker.core.network.DividendClient
import com.stocktracker.core.network.DividendSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live dividend history, direct from Yahoo (see DividendClient) — never
 * through the user's own server. Mirrors useDividends.ts: cached forever on
 * success (errors are not cached, so a failed ticker retries on the next
 * fetch cycle), in-flight dedup per ticker. [NO_FEED_TICKERS] never fetched.
 */
@Singleton
class DividendRepository @Inject constructor(
    private val source: DividendSource,
) {
    private val inFlightLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    private val _dividends = MutableStateFlow<Map<String, List<DividendEvent>>>(emptyMap())
    val dividends: StateFlow<Map<String, List<DividendEvent>>> = _dividends

    suspend fun fetchTickers(tickers: List<String>) = coroutineScope {
        val cached = _dividends.value.keys
        val candidates = tickers.map { it.uppercase() }.distinct()
            .filterNot { it in NO_FEED_TICKERS || it in cached }

        candidates.forEach { ticker ->
            launch {
                // Marking in-flight happens inside this launch body, never in bulk before
                // the loop: PortfolioListViewModel drives fetchTickers() from a
                // collectLatest, so a new positions emission cancels this scope routinely,
                // and a launch cancelled before it starts never runs its body at all. A
                // bulk pre-add would therefore strand those tickers as permanently
                // in-flight, and their dividends would never load again for the life of
                // the process. Same failure QuoteRepository was fixed for in be70bc9.
                val started = inFlightLock.withLock {
                    if (ticker in inFlight) false else { inFlight.add(ticker); true }
                }
                if (!started) return@launch

                try {
                    val events = source.fetchDividendEvents(ticker)
                    _dividends.update { it + (ticker to events) }
                } catch (e: CancellationException) {
                    throw e // cancellation is not a fetch failure — let it propagate
                } catch (_: Exception) {
                    // Don't cache errors — allow retry on the next fetch cycle.
                } finally {
                    // NonCancellable: withLock is itself suspending, so under cancellation
                    // it would throw immediately and skip the removal, recreating the leak.
                    withContext(NonCancellable) {
                        inFlightLock.withLock { inFlight.remove(ticker) }
                    }
                }
            }
        }
    }
}
