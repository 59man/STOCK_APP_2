package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RevolutParserTest {

    private val fakeIsinLookup: IsinLookup = { isins -> isins.associateWith { QuoteInfo(ticker = it, type = PositionType.STOCK) } }
    private val fakeTickerLookup: TickerLookup = { tickers -> tickers.associateWith { QuoteInfo(ticker = it, type = PositionType.STOCK) } }

    @Test
    fun `xau exchange line produces a commodity position`() {
        // Date and qty must both be on the "Exchanged to XAU" line itself —
        // the source matches both regexes against that same line, not a
        // neighboring one. Only the CZK amount is searched for on later lines.
        val lines = listOf(
            "Exchanged to XAU Apr 24, 2024 0.5 XAU exchanged",
            "12,345.67 CZK",
        )
        val result = parseRevolutXau(lines)

        assertEquals(1, result.valid.size)
        val lot = result.valid[0]
        assertEquals("XAU", lot.ticker)
        assertEquals(PositionType.COMMODITY, lot.type)
        assertEquals(0.5, lot.quantity, 1e-9)
        assertEquals("2024-04-24", lot.buyDate)
        assertEquals(12345.67 / 0.5, lot.buyPrice, 1e-6)
    }

    @Test
    fun `xau line without a czk amount within 6 lines is skipped`() {
        val lines = listOf("Exchanged to XAU Apr 24, 2024 0.5 XAU exchanged") + List(6) { "irrelevant" }
        assertEquals(0, parseRevolutXau(lines).valid.size)
    }

    @Test
    fun `trading lines resolve section currency and breakdown symbols`() {
        val lines = listOf(
            "USD Transactions",
            "AAPL Apple Inc US0378331005 10 150.00 1500.00 5%",
            "24 Apr 2024 14:22:15 GMT AAPL Trade - Market 0.5 \$150.25 Buy",
        )
        val parsed = parseRevolutTradingLines(lines)

        assertEquals(1, parsed.txs.size)
        val tx = parsed.txs[0]
        assertEquals("AAPL", tx.symbol)
        assertEquals("2024-04-24", tx.date)
        assertEquals(0.5, tx.qty, 1e-9)
        assertEquals(150.25, tx.price, 1e-9)
        assertEquals("USD", tx.currency)
        assertEquals(false, tx.isSell)

        assertEquals("US0378331005", parsed.bySymbol["AAPL"]?.isin)
        assertEquals("Apple Inc", parsed.bySymbol["AAPL"]?.name)
    }

    @Test
    fun `end to end trading parse resolves ticker via isin breakdown`() = runTest {
        val lines = listOf(
            "USD Transactions",
            "AAPL Apple Inc US0378331005 10 150.00 1500.00 5%",
            "24 Apr 2024 14:22:15 GMT AAPL Trade - Market 0.5 \$150.25 Buy",
        )
        val result = parseRevolutTrading(lines, fakeIsinLookup, fakeTickerLookup)

        assertEquals(1, result.valid.size)
        assertEquals("US0378331005", result.valid[0].ticker) // fake lookup echoes the isin as ticker
    }

    @Test
    fun `symbol never sold during the period falls back to ticker lookup`() = runTest {
        val lines = listOf(
            "USD Transactions",
            "24 Apr 2024 14:22:15 GMT ORPHAN Trade - Market 1 \$10.00 Buy",
        )
        val result = parseRevolutTrading(lines, fakeIsinLookup, fakeTickerLookup)

        assertEquals(1, result.valid.size)
        assertEquals("ORPHAN", result.valid[0].ticker) // no breakdown row — resolved by ticker, not isin
    }
}
