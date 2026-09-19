package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors src/utils/dailyChange.test.ts case for case, with the same numbers. If you add a
 * case there, add it here — this pairing is what keeps the two apps agreeing on what "today's
 * change" means.
 */
class DailyChangeTest {

    @Test fun `reports exactly zero when nothing has traded since local midnight`() {
        val r = dailyChange(10.0, 100.0, 100.0, 1.0, 1.0)
        assertEquals(0.0, r.change, 0.0)
        assertEquals(0.0, r.changePercent, 0.0)
        assertEquals(AnchorMethod.NO_TRADE_TODAY, r.method)
    }

    @Test fun `equals the price-only figure when the display currency is the native one`() {
        val r = dailyChange(10.0, 110.0, 100.0, 1.0, 1.0)
        assertEquals(100.0, r.change, 1e-9)
        assertEquals(100.0, r.priceOnlyChange, 1e-9)
        assertEquals(10.0, r.changePercent, 1e-9)
        assertEquals(AnchorMethod.ANCHORED, r.method)
    }

    @Test fun `counts currency movement when the price itself did not move`() {
        // 10 shares at $100, held in CZK. Price flat, koruna weakened 23.00 -> 23.46.
        val r = dailyChange(10.0, 100.0, 100.0, 23.46, 23.0)
        assertEquals(10 * 100 * (23.46 - 23.0), r.change, 1e-9)
        assertEquals(0.0, r.priceOnlyChange, 1e-9)
        assertEquals(AnchorMethod.ANCHORED, r.method)
    }

    @Test fun `nets price and currency moving in opposite directions`() {
        val r = dailyChange(10.0, 110.0, 100.0, 22.0, 23.0)
        assertEquals(10 * (110 * 22.0 - 100 * 23.0), r.change, 1e-9)
        assertEquals(10 * (110.0 - 100.0) * 22.0, r.priceOnlyChange, 1e-9)
        assertTrue(r.change < r.priceOnlyChange)
        assertTrue(r.change > 0)
    }

    @Test fun `falls back to the previous close when no anchor could be resolved`() {
        val r = dailyChange(10.0, 110.0, null, 1.0, null, prevClose = 100.0)
        assertEquals(100.0, r.change, 1e-9)
        assertEquals(AnchorMethod.PREV_CLOSE_FALLBACK, r.method)
    }

    @Test fun `reports zero, still marked as a fallback, when there is no previous close either`() {
        val r = dailyChange(10.0, 110.0, null, 1.0, null)
        assertEquals(0.0, r.change, 0.0)
        assertEquals(AnchorMethod.PREV_CLOSE_FALLBACK, r.method)
    }

    @Test fun `does not divide by zero on a zero anchor price`() {
        val r = dailyChange(10.0, 110.0, 0.0, 1.0, 1.0)
        assertTrue(r.changePercent.isFinite())
        assertEquals(0.0, r.changePercent, 0.0)
    }

    @Test fun `reports zero for a fully closed position`() {
        val r = dailyChange(0.0, 110.0, 100.0, 1.0, 1.0)
        assertEquals(0.0, r.change, 0.0)
        assertEquals(0.0, r.priceOnlyChange, 0.0)
        assertEquals(0.0, r.changePercent, 0.0)
    }

    @Test fun `falls back to the current rate when only the anchor FX is missing`() {
        val r = dailyChange(10.0, 110.0, 100.0, 23.0, null)
        assertEquals(r.priceOnlyChange, r.change, 1e-9)
        assertEquals(AnchorMethod.ANCHORED, r.method)
    }
}
