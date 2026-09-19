package com.stocktracker.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class EffectiveZoneIdTest {

    @Test fun `an empty setting follows the device`() {
        assertEquals(ZoneId.systemDefault(), AppSettings(timeZoneId = "").effectiveZoneId())
    }

    @Test fun `a stored zone is used`() {
        assertEquals(ZoneId.of("Asia/Tokyo"), AppSettings(timeZoneId = "Asia/Tokyo").effectiveZoneId())
    }

    @Test fun `a zone id that no longer exists falls back rather than throwing`() {
        // A tzdb update can retire a zone id that is already stored on a device.
        assertEquals(ZoneId.systemDefault(), AppSettings(timeZoneId = "Mars/Olympus_Mons").effectiveZoneId())
    }

    @Test fun `whitespace is treated as unset`() {
        assertEquals(ZoneId.systemDefault(), AppSettings(timeZoneId = "   ").effectiveZoneId())
    }
}
