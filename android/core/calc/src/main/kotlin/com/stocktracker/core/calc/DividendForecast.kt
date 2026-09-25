package com.stocktracker.core.calc

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import java.time.LocalDate

data class ForecastEntry(val ticker: String, val date: String, val net: Double, val currency: String)

/**
 * Next 12 months of dividends, estimated by repeating the last 12 months: every event paid in
 * (today − 1 year, today] recurs one year later on the shares held now, net of the default
 * withholding rate. Amounts stay in the event's currency. Mirrors src/utils/dividendForecast.ts.
 */
fun dividendForecast(
    positions: List<Position>,
    dividends: Map<String, List<DividendEvent>>,
    today: String,
): List<ForecastEntry> {
    val yearAgo = LocalDate.parse(today).minusYears(1).toString()
    val held = positions.filter(::isOpenLot).groupBy { it.ticker }.mapValues { (_, lots) -> lots.sumOf { it.quantity } }
    return held.flatMap { (ticker, qty) ->
        if (qty <= 0) return@flatMap emptyList()
        val rate = getDividendTaxRate(ticker)
        (dividends[ticker.uppercase()] ?: emptyList())
            .filter { it.date > yearAgo && it.date <= today }
            .mapNotNull { e ->
                // plusYears clamps 29 Feb to 28 Feb, same as the web.
                val date = LocalDate.parse(e.date).plusYears(1).toString()
                if (date > today) ForecastEntry(ticker, date, qty * e.amount * (1 - rate), e.currency) else null
            }
    }.sortedWith(compareBy<ForecastEntry> { it.date }.thenBy { it.ticker })
}
