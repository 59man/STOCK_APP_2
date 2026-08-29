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

    @Test
    fun `huge return over a near-zero duration returns null instead of an absurd rate`() {
        // A 2x return in 11 days genuinely annualizes to an astronomical rate under compounding —
        // not a meaningful number for a UI to show, so xirr() should decline to return one (same
        // "…" treatment as the same-day buy/sell case) rather than whatever Newton-Raphson
        // converges to outside the sane [-99.9%, 1000%] p.a. bracket.
        val result = xirr(
            listOf(
                CashFlow("2026-08-18", -2250.0),
                CashFlow("2026-08-29", 4567.0),
            )
        )
        assertNull(result)
    }
}
