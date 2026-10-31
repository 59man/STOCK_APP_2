package com.stocktracker.core.calc

import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Quote
import org.junit.Assert.assertEquals
import org.junit.Test

class FundPriceFallbackTest {
    private val fund = "FIOG.PR"
    private fun quote(ticker: String, price: Double) = Quote(ticker, price, 0.0, 0.0, "CZK", ticker, "2026-10-30")

    @Test
    fun `stores a live fund price that differs`() {
        val stored = mapOf(fund to ManualPriceEntry(1.0, "2026-01-01"))
        assertEquals(mapOf(fund to 2.0), fundPricesToStore(listOf(fund), mapOf(fund to quote(fund, 2.0)), stored))
    }

    @Test
    fun `leaves an unchanged price alone, so it does not write on every refresh`() {
        val stored = mapOf(fund to ManualPriceEntry(2.0, "2026-01-01"))
        assertEquals(emptyMap<String, Double>(), fundPricesToStore(listOf(fund), mapOf(fund to quote(fund, 2.0)), stored))
    }

    @Test
    fun `ignores ordinary tickers and funds with no quote`() {
        assertEquals(emptyMap<String, Double>(), fundPricesToStore(listOf("AAPL", fund), mapOf("AAPL" to quote("AAPL", 5.0)), emptyMap()))
    }
}
