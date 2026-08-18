package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRatesTest {

    @Test
    fun `same currency short-circuits regardless of rates`() {
        assertEquals(123.45, convert(123.45, "USD", "USD"), 1e-9)
    }

    @Test
    fun `non-finite amount passes through unchanged`() {
        assertTrue(convert(Double.NaN, "USD", "EUR").isNaN())
    }

    @Test
    fun `cross-rate goes through CZK using default rates`() {
        // 100 USD -> CZK -> EUR: 100 * 25.0 / 27.5
        val expected = 100.0 * 25.0 / 27.5
        assertEquals(expected, convert(100.0, "USD", "EUR"), 1e-9)
    }

    @Test
    fun `CZK is the identity base`() {
        assertEquals(25.0, convert(1.0, "USD", "CZK"), 1e-9)
        assertEquals(0.04, convert(1.0, "CZK", "USD"), 1e-9)
    }

    @Test
    fun `unknown currency falls back to 1_0, treated as CZK`() {
        // unknown "XAU" treated as rate 1.0: 100 * 1.0 / 25.0
        assertEquals(4.0, convert(100.0, "XAU", "USD"), 1e-9)
    }
}
