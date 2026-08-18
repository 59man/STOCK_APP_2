package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvParsersTest {

    private val fakeTickerLookup: TickerLookup = { tickers -> tickers.associateWith { QuoteInfo(ticker = it, type = PositionType.ETF) } }
    private val fakeIsinLookup: IsinLookup = { isins -> isins.associateWith { QuoteInfo(ticker = "RESOLVED", type = PositionType.STOCK) } }

    @Test
    fun `t212 buy row parses and enriches type via ticker lookup`() = runTest {
        val rows = listOf(
            listOf("Action", "Time", "ISIN", "Ticker", "Name", "No. of shares", "Price / share", "Currency (Price/share)"),
            listOf("Market buy", "2024-05-01T10:30:00", "US0378331005", "AAPL", "Apple Inc", "10", "150.00", "USD"),
        )
        val result = parseT212(rows, fakeTickerLookup)!!

        assertEquals(1, result.valid.size)
        val p = result.valid[0]
        assertEquals("AAPL", p.ticker)
        assertEquals("Apple Inc", p.name)
        assertEquals("US0378331005", p.isin)
        assertEquals(10.0, p.quantity, 1e-9)
        assertEquals(150.0, p.buyPrice, 1e-9)
        assertEquals("2024-05-01", p.buyDate)
        assertEquals("USD", p.currency)
        assertEquals("Trading 212", p.broker)
        assertEquals(PositionType.ETF, p.type) // from the fake lookup, proving enrichment ran
    }

    @Test
    fun `t212 sell rows are excluded, not just unmatched`() = runTest {
        val rows = listOf(
            listOf("Action", "Time", "ISIN", "Ticker", "Name", "No. of shares", "Price / share", "Currency (Price/share)"),
            listOf("Market sell", "2024-05-01T10:30:00", "US0378331005", "AAPL", "Apple Inc", "10", "150.00", "USD"),
        )
        val result = parseT212(rows, fakeTickerLookup)
        assertNull(result) // no valid buy rows at all -> null, matching the source
    }

    @Test
    fun `t212 without required columns returns null`() = runTest {
        val rows = listOf(listOf("Foo", "Bar"))
        assertNull(parseT212(rows, fakeTickerLookup))
    }

    @Test
    fun `degiro description regex extracts qty price and currency`() = runTest {
        val rows = listOf(
            listOf("Date", "Product", "ISIN", "Description"),
            listOf("15-03-2024", "Apple Inc", "US0378331005", "Buy 2 Apple Inc @ 87.745 USD"),
        )
        val result = parseDegiro(rows, fakeIsinLookup)!!

        assertEquals(1, result.valid.size)
        val p = result.valid[0]
        assertEquals("RESOLVED", p.ticker) // resolved via isin lookup, ticker starts as the isin itself
        assertEquals("Apple Inc", p.name)
        assertEquals(2.0, p.quantity, 1e-9)
        assertEquals(87.745, p.buyPrice, 1e-9)
        assertEquals("USD", p.currency)
        assertEquals("2024-03-15", p.buyDate)
        assertEquals("Degiro", p.broker)
    }

    @Test
    fun `degiro row whose description is not a buy is skipped`() = runTest {
        val rows = listOf(
            listOf("Date", "Product", "ISIN", "Description"),
            listOf("15-03-2024", "Apple Inc", "US0378331005", "Sell 2 Apple Inc @ 87.745 USD"),
        )
        assertNull(parseDegiro(rows, fakeIsinLookup))
    }

    @Test
    fun `detectCsvFormat recognizes t212 and degiro headers`() {
        assertEquals("t212", detectCsvFormat(listOf("No. of shares", "Price / share")))
        assertEquals("degiro", detectCsvFormat(listOf("Order ID", "ISIN", "Description")))
        assertEquals(null, detectCsvFormat(listOf("Something", "Else")))
    }
}
