package com.stocktracker.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Mirrors src/utils/yahooWindow.test.ts. */
class YahooWindowTest {
    private val now = 1_788_700_000_000L

    @Test fun `bounded ranges keep daily bars`() {
        assertEquals("interval=1d&range=1mo", yahooChartQuery("1mo", now))
        assertEquals("interval=1d&range=5y", yahooChartQuery("5y", now))
    }

    @Test fun `max expands to an explicit epoch to now window with weekly bars`() {
        assertEquals("interval=1wk&period1=0&period2=1788700000", yahooChartQuery("max", now))
    }

    @Test fun `dividends ask for weekly bars over the full window`() {
        assertEquals("interval=1wk&period1=0&period2=1788700000&events=div", yahooDividendQuery(now))
    }

    @Test fun `fx history keeps daily bars for per-date conversion`() {
        assertEquals("interval=1d&period1=0&period2=1788700000", yahooFxHistoryQuery(now))
    }

    @Test fun `no builder emits range=max, which Yahoo answers with 3mo bars regardless of interval`() {
        listOf(yahooChartQuery("max", now), yahooDividendQuery(now), yahooFxHistoryQuery(now))
            .forEach { assertFalse(it.contains("range=max")) }
    }
}
