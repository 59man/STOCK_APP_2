package com.stocktracker.core.importer

import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GenericPdfParserTest {

    private val fakeLookup: IsinLookup = { isins -> isins.associateWith { QuoteInfo(ticker = it, type = PositionType.STOCK) } }

    @Test
    fun `isin with a nearby buy keyword and date produces a hit`() = runTest {
        // Faithfully reproduces a known limitation of this heuristic, not an
        // idealized result: ANY_NUM's separator class `[., ]` treats a plain
        // space as chaining digit groups together, so "01 10 150.00" (the
        // date's day, then the real qty, then the real price, space
        // separated) becomes ONE token ("0110150.00") rather than three —
        // confirmed empirically, not by hand-tracing the regex. Only "2024"
        // and "05" (broken apart by the surrounding hyphens, which aren't in
        // the separator class) survive as their own tokens. That's why every
        // hit here is tagged broker = "Unknown (verify)" — the source has
        // this same behavior (src/utils/pdfParser.ts parseGeneric); this
        // test locks in the exact faithful result, not an idealized one.
        val lines = listOf("Buy US0378331005 2024-05-01 10 150.00")
        val result = parseGeneric(lines, fakeLookup)

        assertEquals(1, result.valid.size)
        val hit = result.valid[0]
        assertEquals("US0378331005", hit.ticker)
        assertEquals("2024-05-01", hit.buyDate)
        assertEquals("Unknown (verify)", hit.broker)
        assertEquals("USD", hit.currency)
        assertEquals(5.0, hit.quantity, 1e-9) // smallest token: "05" (the date's month fragment)
        assertEquals(110150.0, hit.buyPrice, 1e-9) // largest token: "01 10 150.00" chained into one number
    }

    @Test
    fun `isin without a buy keyword nearby is skipped`() = runTest {
        val lines = listOf("Statement summary US0378331005 2024-05-01 10 150.00")
        val result = parseGeneric(lines, fakeLookup)
        assertEquals(0, result.valid.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `isin with a buy keyword but no parseable date is skipped`() = runTest {
        val lines = listOf("Buy US0378331005 10 150.00")
        val result = parseGeneric(lines, fakeLookup)
        assertEquals(0, result.valid.size)
        assertEquals(1, result.skipped)
    }
}
