package com.stocktracker.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatQtyTest {
    @Test fun wholeQuantityHasNoDecimals() = assertEquals("40", formatQty(40.0))

    @Test fun fractionalQuantityDropsPaddingZeros() {
        // The tax card listed gold as "XAU · 3.5000" while the web shows 3.5.
        assertEquals("3.5", formatQty(3.5))
        assertEquals("0.125", formatQty(0.125))
    }

    @Test fun keepsFourDecimalsAtMost() = assertEquals("0.1235", formatQty(0.12345))
}
