package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class RowSortingTest {
    private val rates = mapOf("CZK" to 1.0, "USD" to 23.0)

    private fun row(
        ticker: String = "AAPL",
        name: String = "Apple Inc.",
        type: PositionType = PositionType.STOCK,
        currency: String = "CZK",
        currentValue: Double = 1000.0,
        dailyChange: Double = 10.0,
        totalReturn: Double = 100.0,
    ) = PortfolioRow(
        ids = listOf("id-$ticker"), ticker = ticker, name = name, type = type,
        currency = currency, nativeCurrency = currency, lots = 1, positions = emptyList(),
        totalQuantity = 1.0, avgBuyPrice = 1.0, firstBuyDate = "2024-01-01", currentPrice = 1.0,
        currentValue = currentValue, costBasis = 900.0, pnl = 100.0, pnlPercent = 11.0,
        dividendIncome = 0.0, totalReturn = totalReturn, loading = false, priceIsManual = false,
        isClosed = false, dailyChange = dailyChange,
    )

    @Test
    fun value_sortsOnConvertedAmounts_notRawNumbers() {
        val czk = row(ticker = "KOMB.PR", currency = "CZK", currentValue = 200.0)
        val usd = row(ticker = "AAPL", currency = "USD", currentValue = 100.0)
        val sorted = listOf(czk, usd).sortedForDisplay(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(listOf("AAPL", "KOMB.PR"), sorted.map { it.ticker })
    }

    @Test
    fun today_alsoConvertsBeforeComparing() {
        val czk = row(ticker = "KOMB.PR", currency = "CZK", dailyChange = 50.0)
        val usd = row(ticker = "AAPL", currency = "USD", dailyChange = 5.0)
        val sorted = listOf(czk, usd).sortedForDisplay(SortOrder(SortField.TODAY, ascending = false), "CZK", rates)
        assertEquals(listOf("AAPL", "KOMB.PR"), sorted.map { it.ticker })
    }

    @Test
    fun totalReturn_sortsLossesBelowGains() {
        val loser = row(ticker = "AAA", totalReturn = -500.0)
        val winner = row(ticker = "BBB", totalReturn = 500.0)
        val sorted = listOf(loser, winner).sortedForDisplay(SortOrder(SortField.TOTAL_RETURN, ascending = false), "CZK", rates)
        assertEquals(listOf("BBB", "AAA"), sorted.map { it.ticker })
    }

    @Test
    fun name_isCaseInsensitive() {
        val sorted = listOf(row(ticker = "Z", name = "zeta"), row(ticker = "A", name = "Alpha"))
            .sortedForDisplay(SortOrder(SortField.NAME, ascending = true), "CZK", rates)
        assertEquals(listOf("Alpha", "zeta"), sorted.map { it.name })
    }

    @Test
    fun type_groupsByAssetClass() {
        val etf = row(ticker = "EXUS.DE", type = PositionType.ETF)
        val stock = row(ticker = "AAPL", type = PositionType.STOCK)
        val sorted = listOf(etf, stock).sortedForDisplay(SortOrder(SortField.TYPE, ascending = true), "CZK", rates)
        assertEquals(listOf(PositionType.STOCK, PositionType.ETF), sorted.map { it.type })
    }

    @Test
    fun descending_isTheReverseOfAscending() {
        val rows = listOf(row(ticker = "A", currentValue = 1.0), row(ticker = "B", currentValue = 2.0))
        val asc = rows.sortedForDisplay(SortOrder(SortField.VALUE, ascending = true), "CZK", rates)
        val desc = rows.sortedForDisplay(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(asc.map { it.ticker }.reversed(), desc.map { it.ticker })
    }

    @Test
    fun equalKeys_fallBackToTicker_soOrderIsStable() {
        val rows = listOf(row(ticker = "B", currentValue = 5.0), row(ticker = "A", currentValue = 5.0))
        val sorted = rows.sortedForDisplay(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(listOf("A", "B"), sorted.map { it.ticker })
    }

    @Test
    fun unknownCurrency_doesNotThrow() {
        val weird = row(ticker = "ZZZ", currency = "XXX", currentValue = 5.0)
        val sorted = listOf(weird, row(ticker = "AAA")).sortedForDisplay(SortOrder(), "CZK", rates)
        assertEquals(2, sorted.size)
    }
}
