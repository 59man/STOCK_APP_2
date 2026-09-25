package com.stocktracker.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FxRateMergeTest {
    @Test fun `a pair that failed this refresh keeps its last live rate, not the default`() {
        val live = mapOf("CZK" to 1.0, "USD" to 23.1, "EUR" to 24.3)
        val merged = mergeFxRates(live, mapOf("EUR" to 24.4))
        assertEquals(23.1, merged.getValue("USD"), 0.0)
        assertEquals(24.4, merged.getValue("EUR"), 0.0)
    }
}
