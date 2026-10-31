package com.stocktracker.core.data

import com.stocktracker.core.data.sync.FakePersistApi
import com.stocktracker.core.data.sync.FakeSyncStateDao
import com.stocktracker.core.database.DivTaxOverrideDao
import com.stocktracker.core.database.DivTaxOverrideEntity
import com.stocktracker.core.database.ManualPriceDao
import com.stocktracker.core.database.ManualPriceEntity
import com.stocktracker.core.network.PersistKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePrices(var rows: List<ManualPriceEntity>) : ManualPriceDao {
    override fun observeByPortfolio(portfolioId: String): Flow<List<ManualPriceEntity>> = throw NotImplementedError()
    override suspend fun getByPortfolio(portfolioId: String) = rows
    override suspend fun upsertAll(prices: List<ManualPriceEntity>) { rows = rows + prices }
    override suspend fun delete(portfolioId: String, ticker: String) { rows = rows.filterNot { it.ticker == ticker } }
    override suspend fun deleteByPortfolio(portfolioId: String) { rows = emptyList() }
    override suspend fun replaceByPortfolio(portfolioId: String, prices: List<ManualPriceEntity>) { rows = prices }
}

class RenameTickerTest {
    private val states = FakeSyncStateDao()
    private val api = FakePersistApi()

    private fun price(ticker: String, value: Double) = ManualPriceEntity("p", ticker, value, "2026-10-31")

    @Test
    fun `a manual price follows its ticker and marks the key dirty`() = runTest {
        val dao = FakePrices(listOf(price("OLD", 5.0), price("OTHER", 1.0)))

        assertTrue(ManualPriceRepository(dao, api, states).renameTicker("p", "old", "new"))

        assertEquals(setOf("NEW" to 5.0, "OTHER" to 1.0), dao.rows.map { it.ticker to it.price }.toSet())
        assertEquals(true, states.states[PersistKeys.manualPrices("p")]?.dirty)
    }

    @Test
    fun `a price the new ticker already has is kept`() = runTest {
        val dao = FakePrices(listOf(price("OLD", 5.0), price("NEW", 9.0)))

        ManualPriceRepository(dao, api, states).renameTicker("p", "OLD", "NEW")

        assertEquals(listOf("NEW" to 9.0), dao.rows.map { it.ticker to it.price })
    }

    @Test
    fun `a ticker with no manual price changes nothing`() = runTest {
        val dao = FakePrices(listOf(price("OTHER", 1.0)))

        assertFalse(ManualPriceRepository(dao, api, states).renameTicker("p", "OLD", "NEW"))

        assertEquals(null, states.states[PersistKeys.manualPrices("p")])
    }

    @Test
    fun `crypto keeps its type on import`() {
        assertEquals(com.stocktracker.core.model.PositionType.CRYPTO, mapPositionType("crypto"))
    }
}
