package com.stocktracker.core.calc

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertCrossingTest {
    @Test fun `an armed alert notifies once when crossed, then disarms`() {
        assertEquals(AlertDecision(armed = false, notify = true), evaluateAlert(above = true, threshold = 100.0, armed = true, price = 100.0))
    }

    @Test fun `a disarmed alert stays quiet while the price stays across`() {
        assertEquals(AlertDecision(armed = false, notify = false), evaluateAlert(above = true, threshold = 100.0, armed = false, price = 120.0))
    }

    @Test fun `a disarmed alert re-arms once the price comes back`() {
        assertEquals(AlertDecision(armed = true, notify = false), evaluateAlert(above = true, threshold = 100.0, armed = false, price = 99.0))
    }

    @Test fun `a below alert fires at or under the threshold`() {
        assertEquals(true, evaluateAlert(above = false, threshold = 50.0, armed = true, price = 49.9).notify)
        assertEquals(false, evaluateAlert(above = false, threshold = 50.0, armed = true, price = 50.1).notify)
    }
}
