package com.stocktracker.feature.portfolio

import com.stocktracker.core.designsystem.chart.nearestPointIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class NearestPointIndexTest {
    // 5 points across a 400 px plot starting at 40 px: x = 40, 140, 240, 340, 440.
    @Test fun `snaps to the nearest point`() {
        assertEquals(0, nearestPointIndex(40f, 40f, 400f, 5))
        assertEquals(1, nearestPointIndex(170f, 40f, 400f, 5))
        assertEquals(2, nearestPointIndex(200f, 40f, 400f, 5))
        assertEquals(4, nearestPointIndex(440f, 40f, 400f, 5))
    }

    @Test fun `touches outside the plot clamp to the ends`() {
        assertEquals(0, nearestPointIndex(5f, 40f, 400f, 5))
        assertEquals(4, nearestPointIndex(999f, 40f, 400f, 5))
    }

    @Test fun `a single point is always selected`() {
        assertEquals(0, nearestPointIndex(300f, 40f, 400f, 1))
    }
}
