package com.stocktracker.core.calc

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Test

class DividendsTest {

    private fun position(
        ticker: String,
        qty: Double,
        buyDate: String,
        sellDate: String? = null,
    ) = Position(
        id = "test-id",
        ticker = ticker,
        name = "Test Co",
        type = PositionType.STOCK,
        quantity = qty,
        buyPrice = 100.0,
        buyDate = buyDate,
        currency = "USD",
        sellPrice = if (sellDate != null) 100.0 else null,
        sellDate = sellDate,
    )

    @Test
    fun `unmapped ticker uses default 15 percent rate`() {
        val net = calcNetDividends(
            lots = listOf(position("XYZ", qty = 10.0, buyDate = "2024-01-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "USD")),
            ticker = "XYZ",
        )
        assertEquals(85.0, net, 1e-9)
    }

    @Test
    fun `lot bought after ex-date receives nothing`() {
        val net = calcNetDividends(
            lots = listOf(position("XYZ", qty = 10.0, buyDate = "2024-06-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "USD")),
            ticker = "XYZ",
        )
        assertEquals(0.0, net, 1e-9)
    }

    @Test
    fun `lot sold before ex-date receives nothing`() {
        val net = calcNetDividends(
            lots = listOf(position("XYZ", qty = 10.0, buyDate = "2024-01-01", sellDate = "2024-04-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "USD")),
            ticker = "XYZ",
        )
        assertEquals(0.0, net, 1e-9)
    }

    @Test
    fun `lot sold exactly on ex-date receives nothing`() {
        val net = calcNetDividends(
            lots = listOf(position("XYZ", qty = 10.0, buyDate = "2024-01-01", sellDate = "2024-05-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "USD")),
            ticker = "XYZ",
        )
        assertEquals(0.0, net, 1e-9)
    }

    @Test
    fun `Italian ticker uses 26 percent rate`() {
        val net = calcNetDividends(
            lots = listOf(position("UCG.MI", qty = 10.0, buyDate = "2024-01-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "EUR")),
            ticker = "UCG.MI",
        )
        assertEquals(74.0, net, 1e-9)
    }

    @Test
    fun `per-event override wins over country rate`() {
        val net = calcNetDividends(
            lots = listOf(position("KOMB.PR", qty = 10.0, buyDate = "2024-01-01")),
            dividends = listOf(DividendEvent(date = "2024-05-01", amount = 10.0, currency = "CZK")),
            ticker = "KOMB.PR",
            taxOverrides = mapOf("KOMB.PR::2024-05-01" to 0.0),
        )
        assertEquals(100.0, net, 1e-9)
    }
}
