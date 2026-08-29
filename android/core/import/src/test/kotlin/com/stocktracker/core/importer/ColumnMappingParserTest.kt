package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColumnMappingParserTest {

    private val fakeTickerLookup: TickerLookup = { tickers -> tickers.associateWith { QuoteInfo(ticker = it, type = PositionType.STOCK) } }

    @Test
    fun `mapped columns produce a position and unmapped defaults fill in`() = runTest {
        val rows = listOf(
            listOf("Symbol", "Date", "Qty", "Price", "Name"),
            listOf("AAPL", "2024-05-01", "10", "150.00", "Apple Inc"),
        )
        val mapping = ColumnMapping(
            ticker = 0, date = 1, quantity = 2, buyPrice = 3, name = 4,
            isin = null, currency = null, broker = null, sellDate = null, sellPrice = null,
        )
        val defaults = MappingDefaults(currency = "USD", broker = "Manual import", skipRows = 1)

        val result = parseWithMapping(rows, mapping, defaults, fakeTickerLookup)!!

        assertEquals(1, result.valid.size)
        val p = result.valid[0]
        assertEquals("AAPL", p.ticker)
        assertEquals("Apple Inc", p.name)
        assertEquals(10.0, p.quantity, 1e-9)
        assertEquals(150.0, p.buyPrice, 1e-9)
        assertEquals("2024-05-01", p.buyDate)
        assertEquals("USD", p.currency) // unmapped -> defaults.currency
        assertEquals("Manual import", p.broker) // unmapped -> defaults.broker
        assertNull(p.sellDate)
    }

    @Test
    fun `dmy date format is normalized to iso`() = runTest {
        val rows = listOf(
            listOf("Symbol", "Date", "Qty", "Price"),
            listOf("AAPL", "05.03.2024", "10", "150.00"),
        )
        val mapping = ColumnMapping(0, 1, 2, 3, null, null, null, null, null, null)
        val result = parseWithMapping(rows, mapping, MappingDefaults("USD", "", 1), fakeTickerLookup)!!
        assertEquals("2024-03-05", result.valid[0].buyDate)
    }

    @Test
    fun `row with zero quantity is skipped`() = runTest {
        val rows = listOf(
            listOf("Symbol", "Date", "Qty", "Price"),
            listOf("AAPL", "2024-05-01", "0", "150.00"),
        )
        val mapping = ColumnMapping(0, 1, 2, 3, null, null, null, null, null, null)
        assertNull(parseWithMapping(rows, mapping, MappingDefaults("USD", "", 1), fakeTickerLookup))
    }

    @Test
    fun `autoDetectMapping matches header keywords by position`() {
        val header = listOf("Symbol", "Date", "Qty", "Price", "Name")
        val mapping = autoDetectMapping(header)
        assertEquals(0, mapping.ticker)
        assertEquals(1, mapping.date)
        assertEquals(2, mapping.quantity)
        assertEquals(3, mapping.buyPrice)
        assertEquals(4, mapping.name)
        assertNull(mapping.isin)
        assertNull(mapping.currency)
    }

    @Test
    fun `autoDetectMapping recognizes Ccy as a currency column`() {
        val header = listOf("Symbol", "TradeDate", "Shares", "PricePerShare", "Ccy", "Broker")
        val mapping = autoDetectMapping(header)
        assertEquals(4, mapping.currency)
    }
}
