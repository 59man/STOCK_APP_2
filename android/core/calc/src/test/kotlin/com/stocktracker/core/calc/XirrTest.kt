package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/** Fixtures ported from src/utils/money.test.ts. */
class XirrTest {

    @Test
    fun `10 percent gain over one year`() {
        val result = xirr(
            listOf(
                CashFlow("2020-01-01", -1000.0),
                CashFlow("2021-01-01", 1100.0),
            )
        )
        assertEquals(0.10, result!!, 0.01)
    }

    @Test
    fun `10 percent loss over one year`() {
        val result = xirr(
            listOf(
                CashFlow("2020-01-01", -1000.0),
                CashFlow("2021-01-01", 900.0),
            )
        )
        assertEquals(-0.10, result!!, 0.01)
    }

    @Test
    fun `single cash flow returns null`() {
        val result = xirr(listOf(CashFlow("2020-01-01", -1000.0)))
        assertNull(result)
    }
}
