package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Test

class DedupeTest {

    private fun lot(
        ticker: String = "AAPL.US",
        qty: Double = 10.0,
        price: Double = 150.0,
        date: String = "2024-05-01",
        broker: String? = "XTB",
        sellDate: String? = null,
        sellPrice: Double? = null,
    ) = Position(
        id = "id-${System.nanoTime()}",
        ticker = ticker,
        name = ticker,
        type = PositionType.STOCK,
        quantity = qty,
        buyPrice = price,
        buyDate = date,
        currency = "USD",
        broker = broker,
        sellDate = sellDate,
        sellPrice = sellPrice,
    )

    @Test
    fun `exact re-upload of the same statement is fully deduped`() {
        val existing = listOf(lot())
        val candidates = listOf(lot())

        val (toInsert, duplicates) = filterDuplicates(candidates, existing)

        assertEquals(0, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun `ticker case and broker case do not defeat the match`() {
        val existing = listOf(lot(ticker = "aapl.us", broker = "xtb"))
        val candidates = listOf(lot(ticker = "AAPL.US", broker = "XTB"))

        val (toInsert, duplicates) = filterDuplicates(candidates, existing)

        assertEquals(0, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun `a genuinely new lot in the same statement survives`() {
        val existing = listOf(lot(date = "2024-05-01"))
        val candidates = listOf(lot(date = "2024-05-01"), lot(date = "2024-06-01"))

        val (toInsert, duplicates) = filterDuplicates(candidates, existing)

        assertEquals(1, toInsert.size)
        assertEquals("2024-06-01", toInsert[0].buyDate)
        assertEquals(1, duplicates)
    }

    @Test
    fun `different quantity or price is not a duplicate`() {
        val existing = listOf(lot(qty = 10.0, price = 150.0))
        val candidates = listOf(lot(qty = 11.0, price = 150.0), lot(qty = 10.0, price = 151.0))

        val (toInsert, duplicates) = filterDuplicates(candidates, existing)

        assertEquals(2, toInsert.size)
        assertEquals(0, duplicates)
    }

    @Test
    fun `closed lot only matches when sell date and price also match`() {
        val existing = listOf(lot(sellDate = "2024-07-01", sellPrice = 200.0))
        val candidates = listOf(
            lot(sellDate = "2024-07-01", sellPrice = 200.0),
            lot(sellDate = "2024-07-02", sellPrice = 200.0),
        )

        val (toInsert, duplicates) = filterDuplicates(candidates, existing)

        assertEquals(1, toInsert.size)
        assertEquals("2024-07-02", toInsert[0].sellDate)
        assertEquals(1, duplicates)
    }

    @Test
    fun `empty existing portfolio never dedupes`() {
        val (toInsert, duplicates) = filterDuplicates(listOf(lot(), lot()), emptyList())

        assertEquals(2, toInsert.size)
        assertEquals(0, duplicates)
    }
}
