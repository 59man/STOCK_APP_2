package com.stocktracker.feature.portfolio

import com.stocktracker.core.calc.ChartRange
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * "Jan 15" (or "Jan 15 '24" for multi-year ranges) — mirrors the
 * toLocaleDateString formatting PriceChart.tsx/PortfolioPnLChart.tsx apply
 * before handing dates to recharts. A raw ISO string ("2024-01-15") is
 * ~2.5x wider at the same font size, which crowds or overlaps the sparse
 * axis labels a Yahoo "max"-range chart (as few as ~30-40 coarsened points
 * for an old ticker) still needs room for.
 */
internal fun formatAxisDate(isoDate: String, range: ChartRange): String {
    val date = LocalDate.parse(isoDate)
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.US)
    val showYear = range == ChartRange.ALL || range == ChartRange.FIVE_YEARS || range == ChartRange.THREE_YEARS
    return if (showYear) "$month ${date.dayOfMonth} '${(date.year % 100).toString().padStart(2, '0')}" else "$month ${date.dayOfMonth}"
}
