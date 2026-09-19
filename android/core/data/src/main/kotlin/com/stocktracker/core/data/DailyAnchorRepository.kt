package com.stocktracker.core.data

import com.stocktracker.core.calc.localMidnightEpoch
import com.stocktracker.core.database.DailyAnchorDao
import com.stocktracker.core.database.DailyAnchorEntity
import com.stocktracker.core.network.AnchorClient
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
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun refresh(tickers: List<String>, currencies: List<String>, displayCurrency: String, zone: ZoneId) {
        val today = LocalDate.now(zone).toString()
        val midnight = localMidnightEpoch(zone, Instant.now())
        withContext(Dispatchers.IO) { dao.pruneBefore(today) }

        coroutineScope {
            tickers.map { ticker ->
                async {
                    val key = ticker.uppercase()
                    if (_anchors.value.containsKey(key)) return@async
                    val cached = dao.get(key, today)
                    val anchor = if (cached != null) {
                        Anchor(cached.price, cached.lastTradedAt)
                    } else {
                        val resolved = runCatching { AnchorClient.resolve(ticker, midnight) }.getOrNull()
                        val value = Anchor(resolved?.price, resolved?.lastTradedAt)
                        dao.upsert(DailyAnchorEntity(key, today, value.price, value.lastTradedAt))
                        value
                    }
                    _anchors.value = _anchors.value + (key to anchor)
                }
            }.awaitAll()

            currencies.map { currency ->
                async {
                    val key = "$currency->$displayCurrency"
                    if (_fxAnchors.value.containsKey(key)) return@async
                    val cached = dao.get(key, today)
                    val rate = if (cached != null) {
                        cached.price
                    } else {
                        val resolved = runCatching {
                            AnchorClient.resolveFx(currency, displayCurrency, midnight)
                        }.getOrNull()
                        dao.upsert(DailyAnchorEntity(key, today, resolved, null))
                        resolved
                    }
                    if (rate != null) _fxAnchors.value = _fxAnchors.value + (key to rate)
                }
            }.awaitAll()
        }
    }
}
