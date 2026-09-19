package com.stocktracker.core.calc

import com.stocktracker.core.model.TradingPeriods
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class MarketPhase { PRE_OPEN, OPEN, POST, CLOSED }

/** One session window as wall-clock times **in the viewer's zone**, not the exchange's. */
data class MarketWindow(val phase: MarketPhase, val start: LocalTime, val end: LocalTime)

data class MarketDay(
    val date: LocalDate,
    val windows: List<MarketWindow>,
    /** True when the day was extrapolated from today's pattern rather than reported by Yahoo. */
    val estimated: Boolean = false,
)

/**
 * Yahoo's session windows, rendered in [zone].
 *
 * The conversion is the feature: Yahoo reports epochs and the exchange's own zone, while the
 * question being answered is "when can I trade this, on my clock". A session can therefore end
 * on the next calendar day — an XETRA close reads as 00:30 in Tokyo — and the times are
 * deliberately kept as wall-clock [LocalTime] so the UI can draw them on a single 24-hour bar.
 *
 * The date is the one the *regular* session starts on in [zone]; a null regular period means
 * there is nothing meaningful to show, so the card is omitted rather than rendered empty.
 */
fun marketDay(periods: TradingPeriods, zone: ZoneId): MarketDay? {
    val regular = periods.regular ?: return null

    fun window(phase: MarketPhase, startEpoch: Long, endEpoch: Long) = MarketWindow(
        phase = phase,
        start = Instant.ofEpochSecond(startEpoch).atZone(zone).toLocalTime(),
        end = Instant.ofEpochSecond(endEpoch).atZone(zone).toLocalTime(),
    )

    val windows = buildList {
        periods.pre?.let { add(window(MarketPhase.PRE_OPEN, it.startEpoch, it.endEpoch)) }
        add(window(MarketPhase.OPEN, regular.startEpoch, regular.endEpoch))
        periods.post?.let { add(window(MarketPhase.POST, it.startEpoch, it.endEpoch)) }
    }
    return MarketDay(
        date = Instant.ofEpochSecond(regular.startEpoch).atZone(zone).toLocalDate(),
        windows = windows,
    )
}

/**
 * The phase a given wall-clock time falls in. Each window is half-open — its start belongs to
 * it, its end belongs to whatever follows — so the boundary minute has exactly one answer.
 *
 * A window that wraps past midnight in the viewer's zone (end before start) is handled by
 * treating it as covering everything from the start to the end of the day and from the start
 * of the day to the end.
 */
fun phaseAt(day: MarketDay, time: LocalTime): MarketPhase {
    for (window in day.windows) {
        val inside = if (window.end >= window.start) {
            time >= window.start && time < window.end
        } else {
            time >= window.start || time < window.end
        }
        if (inside) return window.phase
    }
    return MarketPhase.CLOSED
}

/**
 * The next [count] weekdays, repeating today's windows.
 *
 * Marked `estimated` because it is exactly that: Yahoo exposes no exchange holiday calendar,
 * so a market closed for a national holiday will still be listed as open. The UI says so
 * rather than presenting these as fact.
 */
fun nextMarketDays(today: MarketDay, count: Int): List<MarketDay> {
    val days = mutableListOf<MarketDay>()
    var date = today.date
    while (days.size < count) {
        date = date.plusDays(1)
        if (date.dayOfWeek.value >= 6) continue
        days += MarketDay(date = date, windows = today.windows, estimated = true)
    }
    return days
}
