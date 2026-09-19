package com.stocktracker.core.calc

import com.stocktracker.core.model.PriceHistory
import java.time.LocalDate

enum class ReturnPeriod { D1, W1, M1, M3, Y1 }

private fun ReturnPeriod.startFrom(asOf: LocalDate): LocalDate = when (this) {
    ReturnPeriod.D1 -> asOf.minusDays(1)
    ReturnPeriod.W1 -> asOf.minusWeeks(1)
    ReturnPeriod.M1 -> asOf.minusMonths(1)
    ReturnPeriod.M3 -> asOf.minusMonths(3)
    ReturnPeriod.Y1 -> asOf.minusYears(1)
}

/**
 * Percentage change over each period, for the chips on the instrument card.
 *
 * Periods are measured back from the **latest bar's own date**, not from today's wall clock.
 * That is what makes `1D` mean "the last session's move" on a Saturday rather than collapsing
 * to zero because no session has happened since Friday. It also keeps the chips stable: the
 * same history produces the same numbers whenever it is read.
 *
 * (This is a different question from the row's today's-change figure, which deliberately does
 * reset at the viewer's midnight and does read zero on a weekend.)
 *
 * Each period compares the latest close against the last close **on or before** the period's
 * start, so a start landing on a weekend or holiday steps back to the prior trading day.
 *
 * Deliberately not [priceAt]: that resolves to the *nearest* bar and will return one from
 * after the requested date, which for an anchor would measure a shorter period than the chip
 * claims.
 *
 * A period reaching further back than the available history yields null, and its chip is
 * omitted rather than shown as zero.
 */
fun ratesOfReturn(history: PriceHistory): Map<ReturnPeriod, Double?> {
    val last = history.lastOrNull()
    val base = last?.let { runCatching { LocalDate.parse(it.first) }.getOrNull() }
    return ReturnPeriod.entries.associateWith { period ->
        if (last == null || base == null) return@associateWith null
        val anchor = priceOnOrBefore(history, period.startFrom(base).toString())
        if (anchor == null || anchor <= 0.0) null else (last.second - anchor) / anchor * 100.0
    }
}

/** Null when every bar is newer than [date] — the history simply does not reach that far back. */
private fun priceOnOrBefore(history: PriceHistory, date: String): Double? {
    var lo = 0
    var hi = history.size - 1
    var found: Double? = null
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (history[mid].first <= date) {
            found = history[mid].second
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}
