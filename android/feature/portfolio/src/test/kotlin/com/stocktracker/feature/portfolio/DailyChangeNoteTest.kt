package com.stocktracker.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyChangeNoteTest {
    // 2026-09-18 was a Friday.
    private val fridayEpoch = 1_789_745_400L

    @Test fun `a closed market explains its zero and names the last trading day`() {
        val note = dailyChangeNote(fakeRow().copy(dailyChangeMethod = "noTradeToday", lastTradedAt = fridayEpoch))
        assertTrue("expected a weekday name in \"$note\"", note!!.startsWith("closed · last traded "))
    }

    @Test fun `an unknown last trade degrades to a bare closed rather than a placeholder date`() {
        assertEquals("closed", dailyChangeNote(fakeRow().copy(dailyChangeMethod = "noTradeToday", lastTradedAt = null)))
    }

    @Test fun `a fallback row is marked so the number's meaning is not silently different`() {
        assertEquals("prev. close", dailyChangeNote(fakeRow().copy(dailyChangeMethod = "prevCloseFallback")))
    }

    @Test fun `an ordinary anchored row carries no note`() {
        assertNull(dailyChangeNote(fakeRow().copy(dailyChangeMethod = "anchored")))
    }
}
