package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionFactsTest {
    private fun lot(id: String, qty: Double, date: String, broker: String? = null, isin: String? = null, sold: Boolean = false) =
        Position(
            id = id, ticker = "T", name = "T", type = PositionType.ETF, quantity = qty, buyPrice = 10.0, buyDate = date,
            currency = "EUR", broker = broker, isin = isin,
            sellPrice = if (sold) 12.0 else null, sellDate = if (sold) "2025-01-01" else null,
        )

    private fun row(positions: List<Position>, costBasis: Double = 300.0, totalReturn: Double = 30.0) = PortfolioRow(
        ids = positions.map { it.id }, ticker = "T", name = "T", type = PositionType.ETF, currency = "EUR",
        nativeCurrency = "EUR", lots = positions.size, positions = positions, totalQuantity = positions.sumOf { it.quantity },
        avgBuyPrice = 10.0, firstBuyDate = positions.minOf { it.buyDate }, currentPrice = 11.0, currentValue = 220.0,
        costBasis = costBasis, pnl = 20.0, pnlPercent = 6.7, dividendIncome = 10.0, totalReturn = totalReturn,
        loading = false, priceIsManual = false, isClosed = false, dailyChange = 0.0,
    )

    @Test fun `partial sell splits bought, sold and held`() {
        val f = positionFacts(row(listOf(lot("a", 2.0, "2023-01-01"), lot("b", 1.0, "2023-06-01", sold = true))))
        assertEquals(3.0, f.quantityBought, 0.0)
        assertEquals(1.0, f.quantitySold, 0.0)
        assertEquals(2.0, f.quantityHeld, 0.0)
    }

    @Test fun `brokers are deduped in buy order and blanks dropped`() {
        val f = positionFacts(row(listOf(lot("a", 1.0, "2023-01-01", "XTB"), lot("b", 1.0, "2023-02-01", " "), lot("c", 1.0, "2023-03-01", "IBKR"), lot("d", 1.0, "2023-04-01", "XTB"))))
        assertEquals(listOf("XTB", "IBKR"), f.brokers)
    }

    @Test fun `isin is the first non-blank one, else null`() {
        assertEquals("IE00BK5BQT80", positionFacts(row(listOf(lot("a", 1.0, "2023-01-01"), lot("b", 1.0, "2023-02-01", isin = "IE00BK5BQT80")))).isin)
        assertNull(positionFacts(row(listOf(lot("a", 1.0, "2023-01-01")))).isin)
    }

    @Test fun `return percent is total return over cost, null without a cost`() {
        assertEquals(10.0, positionFacts(row(listOf(lot("a", 1.0, "2023-01-01")))).returnPercent!!, 1e-9)
        assertNull(positionFacts(row(listOf(lot("a", 1.0, "2023-01-01")), costBasis = 0.0)).returnPercent)
    }
}
