package com.stocktracker.feature.portfolio

import com.stocktracker.core.calc.ChartRange
import java.time.LocalDate

/**
 * "29/08" (or "29/08/26" for multi-year ranges) — Android-only dd/mm/yy
 * axis format (diverges from the "MMM d 'yy" style PriceChart.tsx/
 * PortfolioPnLChart.tsx still use on web, per explicit user request).
 */
internal fun formatAxisDate(isoDate: String, range: ChartRange): String {
    val date = LocalDate.parse(isoDate)
    val dd = date.dayOfMonth.toString().padStart(2, '0')
    val mm = date.monthValue.toString().padStart(2, '0')
    val showYear = range == ChartRange.ALL || range == ChartRange.FIVE_YEARS || range == ChartRange.THREE_YEARS
    return if (showYear) "$dd/$mm/${(date.year % 100).toString().padStart(2, '0')}" else "$dd/$mm"
}
