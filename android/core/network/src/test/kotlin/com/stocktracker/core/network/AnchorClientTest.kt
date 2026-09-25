package com.stocktracker.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AnchorClientTest {
    @Test fun `only a 404 is a miss worth remembering for the day`() {
        assertEquals(AnchorResponseKind.OK, anchorResponseKind(200))
        assertEquals(AnchorResponseKind.DEFINITIVE_MISS, anchorResponseKind(404))
        assertEquals(AnchorResponseKind.TRANSIENT, anchorResponseKind(429))
        assertEquals(AnchorResponseKind.TRANSIENT, anchorResponseKind(503))
        assertEquals(AnchorResponseKind.TRANSIENT, anchorResponseKind(401))
    }
}
