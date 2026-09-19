package com.stocktracker.core.data

import com.stocktracker.core.database.InstrumentProfileDao
import com.stocktracker.core.database.InstrumentProfileEntity
import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import com.stocktracker.core.network.InstrumentProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeDao : InstrumentProfileDao {
    val rows = MutableStateFlow<Map<String, InstrumentProfileEntity>>(emptyMap())
    override suspend fun get(ticker: String) = rows.value[ticker]
    override fun observe(ticker: String): Flow<InstrumentProfileEntity?> = rows.map { it[ticker] }
    override suspend fun upsert(entity: InstrumentProfileEntity) {
        rows.value = rows.value + (entity.ticker to entity)
    }
}

private class FakeClient(
    private val result: InstrumentProfile?,
    private val fail: Boolean = false,
) : InstrumentProfileSource {
    var calls = 0
    override suspend fun fetch(ticker: String): InstrumentProfile? {
        calls++
        if (fail) throw RuntimeException("network down")
        return result
    }
}

private const val DAY = 24 * 60 * 60 * 1000L

class InstrumentProfileRepositoryTest {

    private val profile = InstrumentProfile(
        ticker = "EXUS.DE",
        exchange = "XETRA",
        exchangeTimeZone = "Europe/Berlin",
        tradingPeriods = TradingPeriods(regular = TradingPeriod(100, 200)),
        sector = "Tech",
    )

    @Test fun `a cold ticker fetches and stores`() = runTest {
        val dao = FakeDao()
        val client = FakeClient(profile)
        val repo = InstrumentProfileRepository(dao, client) { 10 * DAY }
        repo.refreshIfStale("exus.de")
        assertEquals(1, client.calls)
        val stored = repo.observe("EXUS.DE").first()!!
        assertEquals("XETRA", stored.exchange)
        assertEquals(TradingPeriod(100, 200), stored.tradingPeriods!!.regular)
        assertEquals(10 * DAY, stored.fetchedAt)
    }

    @Test fun `a row cached yesterday is not refetched`() = runTest {
        val dao = FakeDao()
        val client = FakeClient(profile)
        val repo = InstrumentProfileRepository(dao, client) { 10 * DAY }
        repo.refreshIfStale("EXUS.DE")
        val repoLater = InstrumentProfileRepository(dao, client) { 11 * DAY }
        repoLater.refreshIfStale("EXUS.DE")
        assertEquals(1, client.calls)
    }

    @Test fun `a row cached eight days ago is refetched`() = runTest {
        val dao = FakeDao()
        val client = FakeClient(profile)
        InstrumentProfileRepository(dao, client) { 10 * DAY }.refreshIfStale("EXUS.DE")
        InstrumentProfileRepository(dao, client) { 18 * DAY }.refreshIfStale("EXUS.DE")
        assertEquals(2, client.calls)
    }

    @Test fun `a fetch failure leaves the cached row intact`() = runTest {
        val dao = FakeDao()
        InstrumentProfileRepository(dao, FakeClient(profile)) { 0 }.refreshIfStale("EXUS.DE")
        InstrumentProfileRepository(dao, FakeClient(null, fail = true)) { 100 * DAY }
            .refreshIfStale("EXUS.DE")
        assertEquals("XETRA", dao.rows.value["EXUS.DE"]!!.exchange)
    }

    @Test fun `an unknown ticker observes as null`() = runTest {
        val repo = InstrumentProfileRepository(FakeDao(), FakeClient(null)) { 0 }
        assertNull(repo.observe("NOPE").first())
    }

    @Test fun `a profile with no session data round-trips as null periods, not empty ones`() = runTest {
        val dao = FakeDao()
        val bare = profile.copy(tradingPeriods = null)
        InstrumentProfileRepository(dao, FakeClient(bare)) { 0 }.refreshIfStale("EXUS.DE")
        assertNull(dao.rows.value["EXUS.DE"]!!.regularStart)
        assertNull(InstrumentProfileRepository(dao, FakeClient(bare)) { 0 }.observe("EXUS.DE").first()!!.tradingPeriods)
    }
}
