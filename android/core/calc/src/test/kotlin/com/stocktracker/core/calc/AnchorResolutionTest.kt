package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Mirrors src/utils/anchorResolution.test.ts. */
class AnchorResolutionTest {
    private val hour = 3600L
    private val prague = ZoneId.of("Europe/Prague")

    // 2026-09-18 00:00 Prague = 2026-09-17 22:00 UTC.
    private val midnight = Instant.parse("2026-09-17T22:00:00Z").epochSecond

    @Test fun `local midnight is the start of the day in the requested zone, not UTC`() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        assertEquals(midnight, localMidnightEpoch(prague, at))
    }

    @Test fun `local midnight differs between zones for the same instant`() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        assertTrue(localMidnightEpoch(prague, at) != localMidnightEpoch(ZoneId.of("Asia/Tokyo"), at))
    }

    @Test fun `a session inside the local day does not need intraday bars`() {
        assertFalse(needsIntraday(midnight + 9 * hour, midnight + 17 * hour, midnight))
    }

    @Test fun `a session in progress at local midnight needs intraday bars`() {
        assertTrue(needsIntraday(midnight - 2 * hour, midnight + 4 * hour, midnight))
    }

    @Test fun `no session window at all needs intraday bars, as for 24-7 crypto`() {
        assertTrue(needsIntraday(null, null, midnight))
    }

    @Test fun `daily resolution takes the last session ending before local midnight`() {
        val bars = listOf(
            AnchorBar(midnight - 3 * 24 * hour, 100.0),
            AnchorBar(midnight - 2 * 24 * hour, 110.0),
            AnchorBar(midnight - 1 * 24 * hour, 120.0),
            AnchorBar(midnight + 9 * hour, 130.0),
        )
        assertEquals(120.0, resolveAnchorFromDaily(bars, midnight, 8 * hour)!!, 1e-9)
    }

    @Test fun `daily resolution returns null when every bar is newer than local midnight`() {
        assertNull(resolveAnchorFromDaily(listOf(AnchorBar(midnight + hour, 5.0)), midnight, 8 * hour))
    }

    @Test fun `daily resolution returns null for an empty series`() {
        assertNull(resolveAnchorFromDaily(emptyList(), midnight, 8 * hour))
    }

    @Test fun `intraday resolution takes the last bar at or before local midnight`() {
        val bars = listOf(
            AnchorBar(midnight - 30 * 60, 90.0),
            AnchorBar(midnight - 5 * 60, 95.0),
            AnchorBar(midnight + 5 * 60, 99.0),
        )
        assertEquals(95.0, resolveAnchorFromIntraday(bars, midnight)!!, 1e-9)
    }

    @Test fun `intraday resolution includes a bar landing exactly on midnight`() {
        assertEquals(42.0, resolveAnchorFromIntraday(listOf(AnchorBar(midnight, 42.0)), midnight)!!, 1e-9)
    }

    @Test fun `intraday resolution returns null when trading only began after midnight`() {
        assertNull(resolveAnchorFromIntraday(listOf(AnchorBar(midnight + 60, 42.0)), midnight))
    }
}
