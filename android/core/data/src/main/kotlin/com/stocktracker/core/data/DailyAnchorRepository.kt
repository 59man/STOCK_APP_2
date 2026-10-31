package com.stocktracker.core.data

import android.util.Log
import com.stocktracker.core.calc.localMidnightEpoch
import com.stocktracker.core.database.DailyAnchorDao
import com.stocktracker.core.database.DailyAnchorEntity
import com.stocktracker.core.network.AnchorClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.stocktracker.core.model.isOnYahoo
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DailyAnchors"

data class Anchor(val price: Double?, val lastTradedAt: Long?)

/**
 * Midnight anchors for the position list, cached per ticker per local date.
 *
 * An anchor is fixed for the whole day, so this is fetched at most once per ticker per day and
 * kept in Room — a cold start in the morning otherwise refetches every holding for no new
 * information. A null price is cached too: a ticker Yahoo does not carry should not be retried
 * on every refresh.
 */
@Singleton
class DailyAnchorRepository @Inject constructor(
    private val dao: DailyAnchorDao,
) {
    private val _anchors = MutableStateFlow<Map<String, Anchor>>(emptyMap())
    val anchors: StateFlow<Map<String, Anchor>> = _anchors

    private val _fxAnchors = MutableStateFlow<Map<String, Double>>(emptyMap())
    val fxAnchors: StateFlow<Map<String, Double>> = _fxAnchors

    /** The local date the in-memory maps belong to. */
    @Volatile
    private var heldDate: String? = null

    suspend fun refresh(
        tickers: List<String>,
        currencies: List<String>,
        displayCurrency: String,
        zone: ZoneId,
        now: Instant = Instant.now(),
    ) {
        val today = LocalDate.ofInstant(now, zone).toString()
        val midnight = localMidnightEpoch(zone, now)
        // A midnight depends on the zone as well as the date. A plain date still sorts before this
        // for dao.pruneBefore, so rows stored under the old key simply age out.
        val dayKey = "$today@${zone.id}"
        // This is a process-wide singleton and the process routinely outlives midnight, so the
        // in-memory maps must be dropped when the date turns — otherwise every ticker already
        // held is skipped below and keeps yesterday's anchor for as long as the app stays alive.
        if (heldDate != dayKey) {
            _anchors.value = emptyMap()
            _fxAnchors.value = emptyMap()
            heldDate = dayKey
        }
        withContext(Dispatchers.IO) { dao.pruneBefore(today) }

        coroutineScope {
            tickers.filter { isOnYahoo(it) }.map { ticker ->
                async {
                    val key = ticker.uppercase()
                    if (_anchors.value.containsKey(key)) return@async
                    val cached = dao.get(key, dayKey)
                    val anchor = if (cached != null) {
                        Anchor(cached.price, cached.lastTradedAt)
                    } else {
                        // A transient failure is neither persisted nor kept in memory, so the
                        // next refresh retries it; the row shows the fallback meanwhile.
                        val resolved = resolveOrNull("anchor $key") { AnchorClient.resolve(ticker, midnight) }
                            ?: return@async
                        val value = Anchor(resolved.price, resolved.lastTradedAt)
                        dao.upsert(DailyAnchorEntity(key, dayKey, value.price, value.lastTradedAt))
                        value
                    }
                    _anchors.value = _anchors.value + (key to anchor)
                }
            }.awaitAll()

            currencies.map { currency ->
                async {
                    val key = "$currency->$displayCurrency"
                    if (_fxAnchors.value.containsKey(key)) return@async
                    val cached = dao.get(key, dayKey)
                    val rate = if (cached != null) {
                        cached.price
                    } else {
                        // Boxed so a legitimately null rate (a pair Yahoo lacks, cacheable) is told
                        // apart from a failed fetch (not cached, retried next refresh).
                        val outcome = resolveOrNull("fx anchor $key") {
                            listOf(AnchorClient.resolveFx(currency, displayCurrency, midnight))
                        } ?: return@async
                        val resolved = outcome.single()
                        dao.upsert(DailyAnchorEntity(key, dayKey, resolved, null))
                        resolved
                    }
                    if (rate != null) _fxAnchors.value = _fxAnchors.value + (key to rate)
                }
            }.awaitAll()
        }
    }
}

/**
 * Runs [block], returning null on failure so the caller skips caching and the next refresh
 * retries. Cancellation is rethrown, never treated as a failed fetch: collectLatest cancels an
 * in-flight refresh whenever quotes update, and caching that as a miss once pinned rows to the
 * fallback for the rest of the day.
 */
private suspend fun <T> resolveOrNull(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "$what not resolved, will retry: ${e.message}")
    null
}
