package com.stocktracker.core.calc

/**
 * Today's change, anchored to midnight in the user's own time zone.
 *
 * Mirrored line-for-line by src/utils/dailyChange.ts, against the same test table — the two
 * apps must not disagree about what this number means.
 */
enum class AnchorMethod {
    /** Measured against the price at the user's local midnight. */
    ANCHORED,

    /** Nothing traded since local midnight, so the change is exactly zero. */
    NO_TRADE_TODAY,

    /** No anchor could be resolved; this is the old exchange-session figure, and the UI says so. */
    PREV_CLOSE_FALLBACK,
}

data class DailyChangeResult(
    /** Display currency, including the currency's own move. */
    val change: Double,
    val changePercent: Double,
    /** Display currency with FX held at today's rate — the figure broker apps show. */
    val priceOnlyChange: Double,
    val method: AnchorMethod,
)

private fun zero(method: AnchorMethod) = DailyChangeResult(0.0, 0.0, 0.0, method)

fun dailyChange(
    quantity: Double,
    currentPrice: Double,
    anchorPrice: Double?,
    currentFx: Double,
    anchorFx: Double?,
    prevClose: Double? = null,
): DailyChangeResult {
    if (quantity == 0.0) return zero(AnchorMethod.ANCHORED)

    // No anchor: report the exchange-session figure rather than a silent zero, which would be
    // indistinguishable from a flat day and would quietly under-report the portfolio total.
    if (anchorPrice == null) {
        if (prevClose == null) return zero(AnchorMethod.PREV_CLOSE_FALLBACK)
        val change = quantity * (currentPrice - prevClose) * currentFx
        val base = quantity * prevClose * currentFx
        return DailyChangeResult(
            change = change,
            changePercent = if (base > 0) change / base * 100 else 0.0,
            priceOnlyChange = change,
            method = AnchorMethod.PREV_CLOSE_FALLBACK,
        )
    }

    // Holding FX flat rather than guessing is deliberate: mixing today's rate against an
    // anchor price from midnight would invent a currency move that never happened.
    val resolvedAnchorFx = anchorFx ?: currentFx

    val nowValue = quantity * currentPrice * currentFx
    val anchorValue = quantity * anchorPrice * resolvedAnchorFx
    val change = nowValue - anchorValue

    return DailyChangeResult(
        change = change,
        changePercent = if (anchorValue > 0) change / anchorValue * 100 else 0.0,
        priceOnlyChange = quantity * (currentPrice - anchorPrice) * currentFx,
        method = if (currentPrice == anchorPrice && resolvedAnchorFx == currentFx) {
            AnchorMethod.NO_TRADE_TODAY
        } else {
            AnchorMethod.ANCHORED
        },
    )
}
