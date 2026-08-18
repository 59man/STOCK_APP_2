package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FioParserTest {

    private val fakeLookup: IsinLookup = { isins -> isins.associateWith { QuoteInfo(ticker = it, type = PositionType.STOCK) } }

    @Test
    fun `buy transaction parses date, isin, qty and price`() = runTest {
        val lines = listOf(
            "12.05.2024 10:30 Apple Inc CZ0008019106 5,00 ks",
            "Nákup CP 1 234,50 CZK",
        )
        val result = parseFio(lines, fakeLookup)

        assertEquals(1, result.valid.size)
        val lot = result.valid[0]
        assertEquals("CZ0008019106", lot.ticker)
        assertEquals("Apple Inc", lot.name)
        assertEquals(5.0, lot.quantity, 1e-9)
        assertEquals(1234.50, lot.buyPrice, 1e-9)
        assertEquals("2024-05-12", lot.buyDate)
        assertNull(lot.sellDate)
    }

    @Test
    fun `buy then sell of the same isin closes the lot via fifo`() = runTest {
        val lines = listOf(
            "01.01.2024 09:00 Apple Inc CZ0008019106 10,00 ks",
            "Nákup CP 100,00 CZK",
            "01.06.2024 09:00 Apple Inc CZ0008019106 10,00 ks",
            "Prodej CP 150,00 CZK",
        )
        val result = parseFio(lines, fakeLookup)

        assertEquals(1, result.valid.size)
        val lot = result.valid[0]
        assertEquals(100.0, lot.buyPrice, 1e-9)
        assertEquals(150.0, lot.sellPrice!!, 1e-9)
        assertEquals("2024-06-01", lot.sellDate)
    }

    @Test
    fun `a row with zero quantity is a dividend, not a trade, and is skipped`() = runTest {
        val lines = listOf(
            "12.05.2024 10:30 Apple Inc CZ0008019106 0,00 ks",
            "Dividenda 5,00 CZK",
        )
        val result = parseFio(lines, fakeLookup)
        assertEquals(0, result.valid.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a line with a date but no isin is not a transaction`() = runTest {
        val lines = listOf("12.05.2024 10:30 Some unrelated note with no isin")
        val result = parseFio(lines, fakeLookup)
        assertEquals(0, result.valid.size)
        assertEquals(0, result.skipped) // never reaches the isin check — continue, not skipped++
    }
}
