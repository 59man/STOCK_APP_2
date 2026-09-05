package com.stocktracker.core.network

import com.stocktracker.core.model.DividendEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors src/utils/dividends.test.ts. */
class DividendClientTest {

    private fun body(currency: String, events: String?) =
        """{"chart":{"result":[{"meta":{"currency":"$currency"}""" +
            (events?.let { ""","events":{"dividends":$it}""" } ?: "") + "}]}}"

    @Test fun `parses events into ISO ex-dates stamped with the feed currency`() {
        val events = parseDividendEvents(body("USD", """{"a":{"date":1716793200,"amount":1.24}}"""))
        assertEquals(listOf(DividendEvent("2024-05-27", 1.24, "USD")), events)
    }

    @Test fun `normalises GBp pence amounts to GBP`() {
        val event = parseDividendEvents(body("GBp", """{"a":{"date":1716793200,"amount":250.0}}""")).single()
        assertEquals("GBP", event.currency)
        assertEquals(2.5, event.amount, 1e-9)
    }

    @Test fun `a successful response carrying no dividend events parses to empty`() {
        assertEquals(emptyList<DividendEvent>(), parseDividendEvents(body("EUR", null)))
    }

    @Test fun `static history fills only the dates the live feed lacks`() {
        val live = parseDividendEvents(body("CZK", """{"a":{"date":1782864000,"amount":30.0}}"""))
        val merged = mergeStatics(live, staticDividendsFor("CZG.PR")) // aliased to COLT.PR
        assertEquals(
            listOf("2021-06-25", "2022-06-01", "2023-06-16", "2024-07-03", "2025-07-03", "2026-07-01"),
            merged.map { it.date },
        )
    }
}
