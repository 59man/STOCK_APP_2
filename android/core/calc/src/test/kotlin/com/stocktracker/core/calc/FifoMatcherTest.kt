package com.stocktracker.core.calc

import com.stocktracker.core.model.PositionType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures ported from src/utils/money.test.ts. */
class FifoMatcherTest {

    private fun rawLot(qty: Double, price: Double, date: String, isSell: Boolean) = RawLot(
        ticker = "TEST",
        name = "Test Co",
        qty = qty,
        price = price,
        date = date,
        currency = "USD",
        broker = "TestBroker",
        type = PositionType.STOCK,
        isSell = isSell,
    )

    @Test
    fun `two buys, no sells yields two open lots`() {
        val result = applyFifo(
            listOf(
                rawLot(qty = 5.0, price = 100.0, date = "2020-01-01", isSell = false),
                rawLot(qty = 3.0, price = 110.0, date = "2020-02-01", isSell = false),
            )
        )
        assertEquals(2, result.size)
        assertEquals(2, result.count { it.sellPrice == null && it.sellDate == null })
    }

    @Test
    fun `partial sell splits the lot into closed and open remainder`() {
        val result = applyFifo(
            listOf(
                rawLot(qty = 10.0, price = 100.0, date = "2020-01-01", isSell = false),
                rawLot(qty = 4.0, price = 150.0, date = "2020-06-01", isSell = true),
            )
        )
        assertEquals(2, result.size)

        val closed = result.single { it.sellPrice != null }
        assertEquals(4.0, closed.quantity, 1e-9)
        assertEquals(100.0, closed.buyPrice, 1e-9)
        assertEquals(150.0, closed.sellPrice!!, 1e-9)

        val open = result.single { it.sellPrice == null }
        assertEquals(6.0, open.quantity, 1e-9)
        assertEquals(100.0, open.buyPrice, 1e-9)
    }

    @Test
    fun `sell spanning two buy lots consumes oldest first`() {
        val result = applyFifo(
            listOf(
                rawLot(qty = 5.0, price = 100.0, date = "2020-01-01", isSell = false),
                rawLot(qty = 5.0, price = 200.0, date = "2020-02-01", isSell = false),
                rawLot(qty = 7.0, price = 300.0, date = "2020-06-01", isSell = true),
            )
        )
        assertEquals(3, result.size)

        val closedLots = result.filter { it.sellPrice != null }.sortedBy { it.buyPrice }
        assertEquals(2, closedLots.size)

        val closed1 = closedLots[0]
        assertEquals(100.0, closed1.buyPrice, 1e-9)
        assertEquals(5.0, closed1.quantity, 1e-9) // fully consumes buy #1

        val closed2 = closedLots[1]
        assertEquals(200.0, closed2.buyPrice, 1e-9)
        assertEquals(2.0, closed2.quantity, 1e-9) // partial

        val open = result.single { it.sellPrice == null }
        assertEquals(200.0, open.buyPrice, 1e-9)
        assertEquals(3.0, open.quantity, 1e-9) // remainder
    }
}
