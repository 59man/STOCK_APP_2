package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    /** The same table runs in src/utils/importCsv.test.ts. */
    @Test
    fun `reads numbers as brokers write them`() {
        mapOf(
            "150,25" to 150.25, "1,000" to 1000.0, "1,234,567" to 1234567.0, "1,234.56" to 1234.56,
            "1.234,56" to 1234.56, "1 234,5" to 1234.5, "87.745" to 87.745, "-3" to -3.0, "0,5" to 0.5,
        ).forEach { (raw, expected) -> assertEquals(raw, expected, parseBrokerNumber(raw), 0.0) }
        listOf("1.2.3", "", "abc", "1,2,3").forEach { assertTrue(it, parseBrokerNumber(it).isNaN()) }
    }

    private val noTickers: TickerLookup = { emptyMap() }
    private val noIsins: IsinLookup = { emptyMap() }
    private val t212Header = listOf("Action", "Time", "ISIN", "Ticker", "Name", "No. of shares", "Price / share", "Currency (Price / share)")

    @Test
    fun `T212 sell rows close lots and a space-separated time reads as a date`() = kotlinx.coroutines.test.runTest {
        val result = parseT212(listOf(
            t212Header,
            listOf("Market buy", "2024-01-02 10:30:00", "US1", "ACME", "Acme", "10", "5", "USD"),
            listOf("Market sell", "2024-03-04T09:00:00", "US1", "ACME", "Acme", "4", "7", "USD"),
        ), noTickers)!!
        val sorted = result.valid.sortedBy { it.quantity }
        assertEquals("2024-03-04", sorted[0].sellDate)
        assertEquals(4.0, sorted[0].quantity, 0.0)
        assertEquals(null, sorted[1].sellDate)
        assertEquals("2024-01-02", sorted[1].buyDate)
    }

    @Test
    fun `a T212 sell it cannot match counts as skipped`() = kotlinx.coroutines.test.runTest {
        val result = parseT212(listOf(
            t212Header,
            listOf("Market buy", "2024-01-02", "US1", "ACME", "Acme", "1", "5", "USD"),
            listOf("Market sell", "2024-03-04", "US1", "ACME", "Acme", "2", "7", "USD"),
        ), noTickers)!!
        assertEquals(1, result.skipped)
    }

    @Test
    fun `Degiro reads a thousands quantity whole and applies sells`() = kotlinx.coroutines.test.runTest {
        val result = parseDegiro(listOf(
            listOf("Date", "Product", "ISIN", "Description", "Order ID"),
            listOf("02-01-2024", "Acme", "US1", "Buy 1,000 Acme @ 5.5 USD", "x"),
            listOf("03-02-2024", "Acme", "US1", "Sell 400 Acme @ 6 USD", "y"),
        ), noIsins)!!
        val sorted = result.valid.sortedBy { it.quantity }
        assertEquals(400.0, sorted[0].quantity, 0.0)
        assertEquals("2024-02-03", sorted[0].sellDate)
        assertEquals(600.0, sorted[1].quantity, 0.0)
    }

    @Test
    fun `a malformed Degiro price skips that row rather than failing the import`() = kotlinx.coroutines.test.runTest {
        val result = parseDegiro(listOf(
            listOf("Date", "Product", "ISIN", "Description", "Order ID"),
            listOf("02-01-2024", "Acme", "US1", "Buy 1 Acme @ 1.2.3 USD", "x"),
            listOf("02-01-2024", "Good", "US2", "Buy 1 Good @ 2 USD", "y"),
        ), noIsins)!!
        assertEquals(1, result.valid.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `the rest of the file settles what a lone 1,234 means`() {
        assertEquals(1.234, parseBrokerNumber("1,234", commaDecimal = true), 0.0)
        assertEquals(1000.0, parseBrokerNumber("1.000", commaDecimal = true), 0.0)
        assertEquals(1234.0, parseBrokerNumber("1,234"), 0.0)
        assertTrue(usesCommaDecimals(listOf("1,234", "150,25")))
        assertTrue(usesCommaDecimals(listOf("1.234,5")))
        assertFalse(usesCommaDecimals(listOf("1,234", "150.25")))
    }
}
