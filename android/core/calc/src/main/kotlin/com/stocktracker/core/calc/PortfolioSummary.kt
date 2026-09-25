package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow

data class DailyTotal(val change: Double, val percent: Double)

/**
 * Portfolio today's change: the sum of the anchored per-row figures, which are already in the
 * display currency — never the legacy previous-close [PortfolioRow.dailyChange]. Mirrors
 * `portfolioDailyChange` in src/utils/rowSorting.ts.
 */
fun portfolioDailyChange(rows: List<PortfolioRow>, totalValueDisplay: Double): DailyTotal {
    val change = rows.sumOf { it.dailyChangeDisplay }
    val prev = totalValueDisplay - change
    return DailyTotal(change, if (prev > 0) change / prev * 100 else 0.0)
}
