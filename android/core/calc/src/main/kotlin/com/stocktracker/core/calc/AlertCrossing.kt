package com.stocktracker.core.calc

/** What one price check does to an alert. */
data class AlertDecision(val armed: Boolean, val notify: Boolean)

/**
 * Fires once per crossing. An armed alert whose condition is met notifies and disarms; a
 * disarmed alert stays silent until the price is back on the other side of the threshold,
 * which re-arms it. Without that, a price sitting above the line would notify on every check.
 */
fun evaluateAlert(above: Boolean, threshold: Double, armed: Boolean, price: Double): AlertDecision {
    val crossed = if (above) price >= threshold else price <= threshold
    return when {
        armed && crossed -> AlertDecision(armed = false, notify = true)
        !armed && !crossed -> AlertDecision(armed = true, notify = false)
        else -> AlertDecision(armed = armed, notify = false)
    }
}
