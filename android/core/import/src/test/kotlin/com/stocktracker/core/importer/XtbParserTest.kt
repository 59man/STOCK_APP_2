package com.stocktracker.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtbParserTest {

    @Test
    fun `buy row computes price from amount over qty`() {
        val rows = listOf(
            listOf("Stock purchase", "AAPL.US", "Apple Inc", "2024-05-01T10:00:00", "-1500.00", "", "OPEN BUY 10/10 @ 150.00"),
        )
        val (lots, skipped) = parseXtbRows(rows, accountCurrency = "EUR")

        assertEquals(0, skipped)
        assertEquals(1, lots.size)
        val lot = lots[0]
        assertEquals("AAPL.US", lot.ticker)
        assertEquals(10.0, lot.qty, 1e-9)
        assertEquals(150.0, lot.price, 1e-9)
        assertEquals("2024-05-01", lot.date)
        assertEquals("EUR", lot.currency)
        assertEquals(false, lot.isSell)
    }

    @Test
    fun `sell row converts the CZ suffix to PR`() {
        val rows = listOf(
            listOf("Stock sale", "CEZ.CZ", "CEZ", "2024-06-01T10:00:00", "2000.00", "", "CLOSE SELL 10 @ 200.00"),
        )
        val (lots, _) = parseXtbRows(rows, accountCurrency = "CZK")

        assertEquals(1, lots.size)
        assertEquals("CEZ.PR", lots[0].ticker)
        assertEquals(200.0, lots[0].price, 1e-9)
        assertEquals(true, lots[0].isSell)
    }

    @Test
    fun `buy row with non-negative amount is skipped`() {
        val rows = listOf(
            listOf("Stock purchase", "AAPL.US", "Apple Inc", "2024-05-01T10:00:00", "1500.00", "", "OPEN BUY 10 @ 150.00"),
        )
        val (lots, skipped) = parseXtbRows(rows, accountCurrency = "EUR")
        assertEquals(0, lots.size)
        assertEquals(1, skipped)
    }

    @Test
    fun `non-trade rows are ignored entirely, not counted as skipped`() {
        val rows = listOf(listOf("Cash deposit", "", "", "2024-05-01", "100.00", "", ""))
        val (lots, skipped) = parseXtbRows(rows, accountCurrency = "EUR")
        assertEquals(0, lots.size)
        assertEquals(0, skipped) // `continue`s before the skip counter in the source
    }

    @Test
    fun `filename prefix determines the account currency`() {
        assertEquals("EUR", accountCurrencyFromFileName("EUR_53675935_2020-01-01_2024-01-01.xlsx"))
        assertNull(accountCurrencyFromFileName("statement.xlsx"))
    }
}
