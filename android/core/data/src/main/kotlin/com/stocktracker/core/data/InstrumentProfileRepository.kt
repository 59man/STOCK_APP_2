package com.stocktracker.core.data

import com.stocktracker.core.database.InstrumentProfileDao
import com.stocktracker.core.database.InstrumentProfileEntity
import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import com.stocktracker.core.network.InstrumentProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Exchange, sector and fees move rarely; a week keeps the screen instant without going stale. */
private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * Instrument metadata, cached in Room so the detail screen renders offline and a cold start in
 * the morning does not refetch everything.
 *
 * Never synced to the user's server: this is derived data in the same category as quotes.
 */
@Singleton
class InstrumentProfileRepository @Inject constructor(
    private val dao: InstrumentProfileDao,
    private val client: InstrumentProfileSource,
) {
    /**
     * Test seam for the staleness check. Not a constructor parameter: Hilt cannot provide a
     * `() -> Long`, and adding a binding for one would be ceremony for a single test.
     */
    internal var now: () -> Long = System::currentTimeMillis

    fun observe(ticker: String): Flow<InstrumentProfile?> =
        dao.observe(ticker.uppercase()).map { it?.toModel() }

    /**
     * A fetch failure deliberately leaves whatever is cached in place: a week-old exchange name
     * is worth far more than an empty card.
     */
    suspend fun refreshIfStale(ticker: String) {
        val key = ticker.uppercase()
        val cached = dao.get(key)
        if (cached != null && now() - cached.fetchedAt < MAX_AGE_MS) return
        val fetched = runCatching { client.fetch(ticker) }.getOrNull() ?: return
        dao.upsert(fetched.copy(fetchedAt = now()).toEntity())
    }
}

private fun InstrumentProfileEntity.toModel() = InstrumentProfile(
    ticker = ticker,
    longName = longName,
    exchange = exchange,
    instrumentType = instrumentType,
    currency = currency,
    exchangeTimeZone = exchangeTimeZone,
    tradingPeriods = periodsOf(),
    fiftyTwoWeekHigh = fiftyTwoWeekHigh,
    fiftyTwoWeekLow = fiftyTwoWeekLow,
    dayHigh = dayHigh,
    dayLow = dayLow,
    volume = volume,
    firstTradeDate = firstTradeDate,
    description = description,
    sector = sector,
    industry = industry,
    website = website,
    country = country,
    fundFamily = fundFamily,
    legalType = legalType,
    expenseRatio = expenseRatio,
    totalNetAssets = totalNetAssets,
    category = category,
    fetchedAt = fetchedAt,
)

/** Null rather than an all-null TradingPeriods, so "no session data" stays distinguishable. */
private fun InstrumentProfileEntity.periodsOf(): TradingPeriods? {
    fun period(start: Long?, end: Long?) = if (start != null && end != null) TradingPeriod(start, end) else null
    val pre = period(preStart, preEnd)
    val regular = period(regularStart, regularEnd)
    val post = period(postStart, postEnd)
    return if (pre == null && regular == null && post == null) {
        null
    } else {
        TradingPeriods(pre, regular, post)
    }
}

private fun InstrumentProfile.toEntity() = InstrumentProfileEntity(
    ticker = ticker.uppercase(),
    longName = longName,
    exchange = exchange,
    instrumentType = instrumentType,
    currency = currency,
    exchangeTimeZone = exchangeTimeZone,
    preStart = tradingPeriods?.pre?.startEpoch,
    preEnd = tradingPeriods?.pre?.endEpoch,
    regularStart = tradingPeriods?.regular?.startEpoch,
    regularEnd = tradingPeriods?.regular?.endEpoch,
    postStart = tradingPeriods?.post?.startEpoch,
    postEnd = tradingPeriods?.post?.endEpoch,
    fiftyTwoWeekHigh = fiftyTwoWeekHigh,
    fiftyTwoWeekLow = fiftyTwoWeekLow,
    dayHigh = dayHigh,
    dayLow = dayLow,
    volume = volume,
    firstTradeDate = firstTradeDate,
    description = description,
    sector = sector,
    industry = industry,
    website = website,
    country = country,
    fundFamily = fundFamily,
    legalType = legalType,
    expenseRatio = expenseRatio,
    totalNetAssets = totalNetAssets,
    category = category,
    fetchedAt = fetchedAt,
)
