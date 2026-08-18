package com.stocktracker.core.calc

import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.model.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowDerivationTest {

    /** Same-currency identity conversion — keeps the arithmetic legible in these tests. */
    private val identity: Convert = { amount, _, _ -> amount }

    private fun openLot(qty: Double, buyPrice: Double, buyDate: String) = Position(
        id = "1", ticker = "AAPL", name = "Apple", type = PositionType.STOCK,
        quantity = qty, buyPrice = buyPrice, buyDate = buyDate, currency = "USD",
    )

    @Test
    fun `open lot with live quote uses quote price`() {
        val lot = openLot(qty = 10.0, buyPrice = 100.0, buyDate = "2024-01-01")
        val quote = Quote(ticker = "AAPL", price = 120.0, change = 2.0, changePercent = 1.6, currency = "USD", name = "Apple", lastUpdated = "2024-06-01")

        val row = deriveRow(
            lots = listOf(lot), quoteRaw = quote, loadingRaw = false, errorRaw = null, manualRaw = null,
            dividends = emptyList(), today = "2024-06-01", convert = identity,
        )

        assertFalse(row.isClosed)
        assertEquals(120.0, row.currentPrice, 1e-9)
        assertEquals(1200.0, row.currentValue, 1e-9)
        assertEquals(1000.0, row.costBasis, 1e-9)
        assertEquals(200.0, row.pnl, 1e-9)
        assertEquals(20.0, row.pnlPercent, 1e-9)
        assertEquals(20.0, row.dailyChange, 1e-9)
        assertFalse(row.priceIsManual)
    }

    @Test
    fun `closed lot values at average sell price with zero current value`() {
        val lot = openLot(qty = 10.0, buyPrice = 100.0, buyDate = "2024-01-01").copy(sellPrice = 150.0, sellDate = "2024-03-01")

        val row = deriveRow(
            lots = listOf(lot), quoteRaw = null, loadingRaw = false, errorRaw = null, manualRaw = null,
            dividends = emptyList(), today = "2024-06-01", convert = identity,
        )

        assertTrue(row.isClosed)
        assertEquals(150.0, row.currentPrice, 1e-9)
        assertEquals(0.0, row.currentValue, 1e-9)
        assertEquals(500.0, row.pnl, 1e-9)
        assertEquals(50.0, row.pnlPercent, 1e-9)
    }

    @Test
    fun `manual price used when no quote is available`() {
        val lot = openLot(qty = 5.0, buyPrice = 50.0, buyDate = "2024-01-01")
        val manual = ManualPriceEntry(price = 60.0, updatedAt = "2024-05-01")

        val row = deriveRow(
            lots = listOf(lot), quoteRaw = null, loadingRaw = false, errorRaw = null, manualRaw = manual,
            dividends = emptyList(), today = "2024-06-01", convert = identity,
        )

        assertTrue(row.priceIsManual)
        assertEquals(60.0, row.currentPrice, 1e-9)
        assertEquals(300.0, row.currentValue, 1e-9)
        assertEquals(50.0, row.pnl, 1e-9)
        assertEquals("2024-05-01", row.manualPriceDate)
    }

    @Test
    fun `no quote and no manual falls back to break-even at avg buy price`() {
        val lot = openLot(qty = 5.0, buyPrice = 50.0, buyDate = "2024-01-01")

        val row = deriveRow(
            lots = listOf(lot), quoteRaw = null, loadingRaw = false, errorRaw = null, manualRaw = null,
            dividends = emptyList(), today = "2024-06-01", convert = identity,
        )

        assertEquals(50.0, row.currentPrice, 1e-9)
        assertEquals(0.0, row.pnl, 1e-9)
        assertFalse(row.priceIsManual)
        assertNull(row.irr) // no usable price source — must not guess
    }

    @Test
    fun `portfolio irr matches single-position xirr`() {
        val position = openLot(qty = 1.0, buyPrice = 1000.0, buyDate = "2020-01-01")
        val row = PortfolioRow(
            ids = listOf(position.id), ticker = "AAPL", name = "Apple", type = PositionType.STOCK,
            currency = "USD", nativeCurrency = "USD", lots = 1, positions = listOf(position),
            totalQuantity = 1.0, avgBuyPrice = 1000.0, firstBuyDate = "2020-01-01",
            currentPrice = 1100.0, currentValue = 1100.0, costBasis = 1000.0, pnl = 100.0,
            pnlPercent = 10.0, dividendIncome = 0.0, totalReturn = 100.0, loading = false,
            priceIsManual = false, irr = 0.10, isClosed = false, dailyChange = 0.0,
        )

        val irr = computePortfolioIrr(
            positions = listOf(position), rows = listOf(row), dividendsByTicker = emptyMap(),
            displayCurrency = "USD", today = "2021-01-01", convert = identity,
        )

        assertEquals(0.10, irr!!, 0.01)
    }

    @Test
    fun `portfolio irr is null while any row is still loading`() {
        val position = openLot(qty = 1.0, buyPrice = 1000.0, buyDate = "2020-01-01")
        val row = PortfolioRow(
            ids = listOf(position.id), ticker = "AAPL", name = "Apple", type = PositionType.STOCK,
            currency = "USD", nativeCurrency = "USD", lots = 1, positions = listOf(position),
            totalQuantity = 1.0, avgBuyPrice = 1000.0, firstBuyDate = "2020-01-01",
            currentPrice = 1000.0, currentValue = 1000.0, costBasis = 1000.0, pnl = 0.0,
            pnlPercent = 0.0, dividendIncome = 0.0, totalReturn = 0.0, loading = true,
            priceIsManual = false, irr = null, isClosed = false, dailyChange = 0.0,
        )

        val irr = computePortfolioIrr(
            positions = listOf(position), rows = listOf(row), dividendsByTicker = emptyMap(),
            displayCurrency = "USD", today = "2021-01-01", convert = identity,
        )

        assertNull(irr)
    }
}
