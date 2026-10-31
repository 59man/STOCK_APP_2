package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same table runs in src/utils/dedupe.test.ts. */
class DedupeTest {
    private fun lot(id: String, quantity: Double, sold: Boolean = false) = Position(
        id = id, ticker = "ACME", name = "Acme", type = PositionType.STOCK, quantity = quantity, buyPrice = 10.0,
        buyDate = "2024-01-02", currency = "USD", broker = "XTB",
        sellDate = if (sold) "2024-06-01" else null, sellPrice = if (sold) 15.0 else null,
    )
    private fun shape(ps: List<Position>) = ps.map { Triple(it.id, it.quantity, it.sellDate) }.sortedBy { it.first }

    @Test
    fun `adds everything to an empty portfolio`() {
        val plan = planImport(emptyList(), listOf(lot("n", 5.0)))
        assertEquals(1, plan.added)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun `drops an identical re-import`() {
        val plan = planImport(listOf(lot("e", 10.0)), listOf(lot("n", 10.0)))
        assertEquals(shape(listOf(lot("e", 10.0))), shape(plan.positions))
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `counts a split lot once when the same shares come back split`() {
        val existing = listOf(lot("c", 4.0, sold = true), lot("o", 6.0))
        val plan = planImport(existing, listOf(lot("n1", 4.0, sold = true), lot("n2", 6.0)))
        assertEquals(shape(existing), shape(plan.positions))
        assertEquals(2, plan.duplicates)
    }

    @Test
    fun `closes open shares when a newer statement contains the sale`() {
        val plan = planImport(listOf(lot("e", 10.0)), listOf(lot("n1", 4.0, sold = true), lot("n2", 6.0)))
        assertEquals(shape(listOf(lot("e", 6.0), lot("n1", 4.0, sold = true))), shape(plan.positions))
        assertEquals(1, plan.added)
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `removes an open lot its sale closes entirely`() {
        val plan = planImport(listOf(lot("e", 10.0)), listOf(lot("n", 10.0, sold = true)))
        assertEquals(shape(listOf(lot("n", 10.0, sold = true))), shape(plan.positions))
    }

    @Test
    fun `does not reopen shares when an older statement arrives after a newer one`() {
        val existing = listOf(lot("c", 4.0, sold = true), lot("o", 6.0))
        val plan = planImport(existing, listOf(lot("n", 10.0)))
        assertEquals(shape(existing), shape(plan.positions))
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `adds only the shares beyond what is already there`() {
        val plan = planImport(listOf(lot("e", 10.0)), listOf(lot("n", 15.0)))
        assertEquals(shape(listOf(lot("e", 10.0), lot("n", 5.0))), shape(plan.positions))
    }

    @Test
    fun `treats a different ticker, date, price or broker as a different buy`() {
        val base = lot("n", 10.0)
        listOf(base.copy(ticker = "OTHER"), base.copy(buyDate = "2024-01-03"), base.copy(buyPrice = 11.0), base.copy(broker = "Revolut")).forEach {
            assertEquals(1, planImport(listOf(lot("e", 10.0)), listOf(it)).added)
        }
    }
}
