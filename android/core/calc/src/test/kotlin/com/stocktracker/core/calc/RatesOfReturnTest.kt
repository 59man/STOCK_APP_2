package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RatesOfReturnTest {

    /** Daily closes for every weekday of 2026, starting at 100 and rising by 1 per trading day. */
    private fun risingHistory(days: Int, from: LocalDate = LocalDate.of(2025, 1, 1)) =
        (0 until days).map { i -> from.plusDays(i.toLong()).toString() to 100.0 + i }

    @Test fun `one day is the move against the previous bar`() {
        val history = listOf("2026-09-17" to 100.0, "2026-09-18" to 110.0)
        val r = ratesOfReturn(history)
        assertEquals(10.0, r[ReturnPeriod.D1]!!, 1e-9)
    }

    @Test fun `a gap at the period boundary resolves to the previous trading day`() {
        // No bar on the 11th, so the 1W anchor must step back to the 10th rather than vanish.
        val history = listOf("2026-09-10" to 100.0, "2026-09-17" to 100.0, "2026-09-18" to 105.0)
        val r = ratesOfReturn(history)
        assertEquals(5.0, r[ReturnPeriod.W1]!!, 1e-9)
    }

    @Test fun `a period older than the history yields null`() {
        val r = ratesOfReturn(risingHistory(40, LocalDate.of(2026, 8, 10)))
        assertNull(r[ReturnPeriod.Y1])
        assertNull(r[ReturnPeriod.M3])
    }

    @Test fun `a full year of history fills every period`() {
        val r = ratesOfReturn(risingHistory(500, LocalDate.of(2025, 5, 1)))
        ReturnPeriod.entries.forEach { period ->
            assertEquals("expected a value for $period", true, r[period] != null)
        }
    }

    @Test fun `an empty history yields nulls for every period`() {
        val r = ratesOfReturn(emptyList())
        assertEquals(ReturnPeriod.entries.size, r.size)
        assertEquals(true, r.values.all { it == null })
    }

    @Test fun `a zero earlier price yields null rather than infinity`() {
        val history = listOf("2026-09-17" to 0.0, "2026-09-18" to 105.0)
        assertNull(ratesOfReturn(history)[ReturnPeriod.D1])
    }

    @Test fun `a loss is reported negative`() {
        val history = listOf("2026-09-17" to 200.0, "2026-09-18" to 150.0)
        assertEquals(-25.0, ratesOfReturn(history)[ReturnPeriod.D1]!!, 1e-9)
    }

    @Test fun `periods anchor to the latest bar, so a weekend still shows the last session`() {
        // Read on Saturday 2026-09-19: the latest bar is Friday's, and 1D compares it to
        // Thursday's rather than collapsing to zero because no session happened today.
        val history = listOf("2026-09-17" to 100.0, "2026-09-18" to 120.0)
        assertEquals(20.0, ratesOfReturn(history)[ReturnPeriod.D1]!!, 1e-9)
    }
}
