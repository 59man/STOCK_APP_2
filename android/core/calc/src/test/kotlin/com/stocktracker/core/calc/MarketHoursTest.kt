package com.stocktracker.core.calc

import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

class MarketHoursTest {

    /**
     * Recorded from a real EXUS.DE response: pre 07:00-09:00, regular 09:00-17:30,
     * post 17:30-20:30, in Prague/Berlin wall clock.
     */
    private val xetra = TradingPeriods(
        pre = TradingPeriod(1789707600, 1789714800),
        regular = TradingPeriod(1789714800, 1789745400),
        post = TradingPeriod(1789745400, 1789756200),
    )

    @Test fun `a Berlin session rendered in Prague keeps its local wall clock`() {
        val day = marketDay(xetra, ZoneId.of("Europe/Prague"))!!
        val regular = day.windows.single { it.phase == MarketPhase.OPEN }
        assertEquals(LocalTime.of(9, 0), regular.start)
        assertEquals(LocalTime.of(17, 30), regular.end)
    }

    @Test fun `the same session rendered in Tokyo shifts by seven hours`() {
        // The whole point of the feature: a Japanese user sees when they can trade XETRA,
        // in their own clock, not Berlin's.
        val day = marketDay(xetra, ZoneId.of("Asia/Tokyo"))!!
        val regular = day.windows.single { it.phase == MarketPhase.OPEN }
        assertEquals(LocalTime.of(16, 0), regular.start)
        assertEquals(LocalTime.of(0, 30), regular.end)
    }

    @Test fun `pre and post windows are reported alongside the regular one`() {
        val day = marketDay(xetra, ZoneId.of("Europe/Prague"))!!
        assertEquals(LocalTime.of(7, 0), day.windows.single { it.phase == MarketPhase.PRE_OPEN }.start)
        assertEquals(LocalTime.of(20, 30), day.windows.single { it.phase == MarketPhase.POST }.end)
    }

    @Test fun `a session with no pre or post yields only the regular window`() {
        val day = marketDay(TradingPeriods(regular = xetra.regular), ZoneId.of("Europe/Prague"))!!
        assertEquals(listOf(MarketPhase.OPEN), day.windows.map { it.phase })
    }

    @Test fun `no regular period at all yields null rather than an empty day`() {
        assertEquals(null, marketDay(TradingPeriods(), ZoneId.of("Europe/Prague")))
    }

    @Test fun `phaseAt reports each phase at its own minute`() {
        val day = marketDay(xetra, ZoneId.of("Europe/Prague"))!!
        assertEquals(MarketPhase.CLOSED, phaseAt(day, LocalTime.of(6, 59)))
        assertEquals(MarketPhase.PRE_OPEN, phaseAt(day, LocalTime.of(7, 0)))
        assertEquals(MarketPhase.PRE_OPEN, phaseAt(day, LocalTime.of(8, 59)))
        assertEquals(MarketPhase.OPEN, phaseAt(day, LocalTime.of(9, 0)))
        assertEquals(MarketPhase.OPEN, phaseAt(day, LocalTime.of(17, 29)))
        assertEquals(MarketPhase.POST, phaseAt(day, LocalTime.of(17, 30)))
        assertEquals(MarketPhase.CLOSED, phaseAt(day, LocalTime.of(20, 30)))
        assertEquals(MarketPhase.CLOSED, phaseAt(day, LocalTime.of(23, 59)))
    }

    @Test fun `next days skip the weekend and are all marked estimated`() {
        val friday = marketDay(xetra, ZoneId.of("Europe/Prague"))!!
        assertFalse(friday.estimated)
        val next = nextMarketDays(friday, 4)
        assertEquals(listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY"), next.map { it.date.dayOfWeek.name })
        assertTrue(next.all { it.estimated })
    }

    @Test fun `next days repeat today's windows`() {
        val today = marketDay(xetra, ZoneId.of("Europe/Prague"))!!
        val tomorrow = nextMarketDays(today, 1).single()
        assertEquals(today.windows.map { it.start to it.end }, tomorrow.windows.map { it.start to it.end })
    }
}
