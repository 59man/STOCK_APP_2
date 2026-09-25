package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioSummaryTest {
    private fun row(legacy: Double, anchored: Double) = PortfolioRow(
        ids = listOf("x"), ticker = "X", name = "X", type = PositionType.STOCK, currency = "USD", nativeCurrency = "USD",
        lots = 1, positions = emptyList(), totalQuantity = 1.0, avgBuyPrice = 1.0, firstBuyDate = "2024-01-01",
        currentPrice = 1.0, currentValue = 510.0, costBasis = 500.0, pnl = 10.0, pnlPercent = 2.0, dividendIncome = 0.0,
        totalReturn = 10.0, loading = false, priceIsManual = false, isClosed = false,
        dailyChange = legacy, dailyChangeDisplay = anchored,
    )

    @Test
    fun `sums the anchored display figure, never the legacy previous-close one`() {
        val total = portfolioDailyChange(listOf(row(-500.0, 10.0), row(-500.0, 10.0)), totalValueDisplay = 1020.0)
        assertEquals(20.0, total.change, 1e-9)
        assertEquals(2.0, total.percent, 1e-9)
    }

    @Test
    fun `zero previous value gives zero percent`() {
        assertEquals(0.0, portfolioDailyChange(emptyList(), 0.0).percent, 0.0)
    }
}
