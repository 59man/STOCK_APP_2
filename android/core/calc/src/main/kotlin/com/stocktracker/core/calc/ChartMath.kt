package com.stocktracker.core.calc

import com.stocktracker.core.model.DivTaxOverrides
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.FX_CONVERTED_SET
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.PriceHistory
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.Quote
import java.time.DayOfWeek
import java.time.LocalDate

/** Chart range selector — mirrors the `Range` union shared by PriceChart.tsx and PortfolioPnLChart.tsx. */
enum class ChartRange(val yahooParam: String, val label: String) {
    ONE_MONTH("1mo", "1M"),
    THREE_MONTHS("3mo", "3M"),
    SIX_MONTHS("6mo", "6M"),
    ONE_YEAR("1y", "1Y"),
    THREE_YEARS("3y", "3Y"),
    FIVE_YEARS("5y", "5Y"),
    ALL("max", "All"),
}

/** Calendar cutoff for [range]; `"0000-00-00"` for ALL so every date passes. Mirrors rangeStartDate in PortfolioPnLChart.tsx. */
fun rangeStartDate(range: ChartRange, today: LocalDate = LocalDate.now()): String = when (range) {
    ChartRange.ONE_MONTH -> today.minusMonths(1).toString()
    ChartRange.THREE_MONTHS -> today.minusMonths(3).toString()
    ChartRange.SIX_MONTHS -> today.minusMonths(6).toString()
    ChartRange.ONE_YEAR -> today.minusYears(1).toString()
    ChartRange.THREE_YEARS -> today.minusYears(3).toString()
    ChartRange.FIVE_YEARS -> today.minusYears(5).toString()
    ChartRange.ALL -> "0000-00-00"
}

/**
 * Last point at-or-before [date], falling back to the first point after when
 * that's actually closer — a round-the-clock feed's live/final bar can be
 * timestamped just past UTC midnight, landing one calendar day later than a
 * same-session bar it needs to line up with. Ported from priceAt in
 * PortfolioPnLChart.tsx (HistoryClient.priceAtStep is the same logic on the
 * fetch side, kept separate since it runs once per fetch, not once per date).
 */
fun priceAt(history: PriceHistory, date: String): Double? {
    var lo = 0
    var hi = history.size - 1
    var beforeIdx = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (history[mid].first <= date) { beforeIdx = mid; lo = mid + 1 } else hi = mid - 1
    }
    val before = history.getOrNull(beforeIdx)
    val after = history.getOrNull(lo)
    if (before == null) return after?.second
    if (after == null) return before.second
    val targetDay = LocalDate.parse(date).toEpochDay()
    val beforeGapDays = targetDay - LocalDate.parse(before.first).toEpochDay()
    val afterGapDays = LocalDate.parse(after.first).toEpochDay() - targetDay
    return if (afterGapDays <= 1 && afterGapDays <= beforeGapDays) after.second else before.second
}

/**
 * Linearly interpolates day-by-day between sorted (date, price) knots, so a
 * manual-priced fund's gain accrues gradually across its whole holding
 * period instead of sitting flat then jumping on the single day the price
 * was last entered. Ported from interpolateDaily in PortfolioPnLChart.tsx.
 */
fun interpolateDaily(knots: PriceHistory): PriceHistory {
    if (knots.size <= 1) return knots
    val out = mutableListOf<Pair<String, Double>>()
    for (i in 0 until knots.size - 1) {
        val (startDate, startPrice) = knots[i]
        val (endDate, endPrice) = knots[i + 1]
        val startDay = LocalDate.parse(startDate).toEpochDay()
        val endDay = LocalDate.parse(endDate).toEpochDay()
        val totalDays = (endDay - startDay).toInt()
        if (totalDays <= 0) {
            out.add(startDate to startPrice)
            continue
        }
        for (d in 0 until totalDays) {
            val date = LocalDate.ofEpochDay(startDay + d).toString()
            out.add(date to startPrice + (endPrice - startPrice) * (d.toDouble() / totalDays))
        }
    }
    out.add(knots.last())
    return out
}

/** One ticker's price series plus the currency it's denominated in — resolved by the caller (see [buildEffectiveHistories]). */
data class TickerChartHistory(val points: PriceHistory, val currency: String)

/**
 * Fills in a synthetic history (buy-price → manual-price knots, linearly
 * interpolated) for any ticker with no real fetched history but a manual
 * price, and injects today's live quote as the final bar for tickers that do
 * have real history — so the chart's last point matches the table's live
 * intraday total return instead of lagging at yesterday's close. A ticker
 * with neither a fetched history nor a manual price is simply absent from
 * the result. Ported from the effectiveHistories useMemo in PortfolioPnLChart.tsx.
 */
fun buildEffectiveHistories(
    histories: Map<String, TickerChartHistory>,
    manualPrices: Map<String, ManualPriceEntry>,
    quotes: Map<String, Quote>,
    positions: List<Position>,
    tickers: List<String>,
    today: LocalDate = LocalDate.now(),
    isWeekend: Boolean = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY,
): Map<String, TickerChartHistory> {
    val todayStr = today.toString()
    val map = histories.toMutableMap()
    tickers.forEach { ticker ->
        val key = ticker.uppercase()
        val existing = map[key]
        if (existing != null && existing.points.isNotEmpty()) {
            val skipWeekend = isWeekend && key !in FX_CONVERTED_SET
            val liveQuote = if (!skipWeekend) quotes[key] else null
            if (liveQuote != null && liveQuote.currency == existing.currency && liveQuote.price > 0 && liveQuote.price.isFinite()) {
                val pts = existing.points.toMutableList()
                if (pts.isNotEmpty() && pts.last().first == todayStr) {
                    pts[pts.size - 1] = todayStr to liveQuote.price
                } else {
                    pts.add(todayStr to liveQuote.price)
                }
                map[key] = existing.copy(points = pts)
            }
            return@forEach
        }
        val mp = manualPrices[key] ?: return@forEach
        val tickerLots = positions.filter { it.ticker.uppercase() == key }
        if (tickerLots.isEmpty()) return@forEach
        val knots = linkedMapOf<String, Double>()
        tickerLots.forEach { p -> knots.putIfAbsent(p.buyDate, p.buyPrice) }
        knots[mp.updatedAt.take(10).ifEmpty { todayStr }] = mp.price
        val sortedKnots = knots.entries.sortedBy { it.key }.map { it.key to it.value }
        map[key] = TickerChartHistory(interpolateDaily(sortedKnots), currency = tickerLots.first().currency)
    }
    return map
}

/** One portfolio chart data point — mirrors ChartPoint in PortfolioPnLChart.tsx. */
data class PortfolioChartPoint(val date: String, val pnl: Double, val costBasis: Double, val currentValue: Double)

/**
 * Builds the portfolio total-return / value time series. Ported from the
 * chartData useMemo in PortfolioPnLChart.tsx — every amount is converted at
 * *that date's* FX rate (via [fxHistories], step-looked-up through [priceAt])
 * rather than today's spot rate, so historical points don't drift with
 * today's rate. currentValue reuses the same price-gain figure as pnl for
 * each lot (rather than an independent price(date)×qty conversion) so
 * `currentValue - costBasis` always equals the price-P&L component of pnl
 * exactly — see the comment in the source for why a naive reconversion would
 * silently disagree.
 */
fun buildPortfolioChartData(
    positions: List<Position>,
    dividendsByTicker: Map<String, List<DividendEvent>>,
    effectiveHistories: Map<String, TickerChartHistory>,
    fxHistories: Map<String, PriceHistory>,
    range: ChartRange,
    displayCurrency: String,
    taxOverrides: DivTaxOverrides = emptyMap(),
    spotConvert: (Double, String, String) -> Double,
): List<PortfolioChartPoint> {
    if (effectiveHistories.isEmpty()) return emptyList()

    val firstBuyDate = positions.minOfOrNull { it.buyDate } ?: "0000-00-00"
    val cutoff = if (range == ChartRange.ALL) firstBuyDate else rangeStartDate(range)

    val dateSet = sortedSetOf<String>()
    effectiveHistories.values.forEach { h -> h.points.forEach { (d, _) -> if (d >= cutoff) dateSet.add(d) } }
    if (dateSet.isEmpty()) return emptyList()

    fun fxAt(currency: String, date: String): Double? =
        if (currency == "CZK") 1.0 else priceAt(fxHistories[currency] ?: emptyList(), date)

    fun convertAt(amount: Double, from: String, to: String, date: String): Double {
        if (from == to) return amount
        val f = fxAt(from, date)
        val t = fxAt(to, date)
        return if (f != null && t != null) (amount * f) / t else spotConvert(amount, from, to)
    }

    return dateSet.map { date ->
        var pricePnl = 0.0
        var costBasis = 0.0
        var currentValue = 0.0

        positions.forEach { pos ->
            if (pos.buyDate > date) return@forEach

            val sellDate = pos.sellDate
            val sellPrice = pos.sellPrice
            if (sellDate != null && sellDate <= date && sellPrice != null) {
                pricePnl += convertAt((sellPrice - pos.buyPrice) * pos.quantity, pos.currency, displayCurrency, sellDate)
                return@forEach
            }

            val costBasisInDisplay = convertAt(pos.buyPrice * pos.quantity, pos.currency, displayCurrency, pos.buyDate)
            costBasis += costBasisInDisplay

            val hist = effectiveHistories[pos.ticker.uppercase()]
            val price = if (hist != null && hist.points.isNotEmpty()) priceAt(hist.points, date) else null
            if (hist == null || price == null) {
                currentValue += costBasisInDisplay
                return@forEach
            }
            val buyInHistCurrency = convertAt(pos.buyPrice, pos.currency, hist.currency, pos.buyDate)
            val lotPricePnl = convertAt((price - buyInHistCurrency) * pos.quantity, hist.currency, displayCurrency, date)
            pricePnl += lotPricePnl
            currentValue += costBasisInDisplay + lotPricePnl
        }

        var divPnl = 0.0
        positions.forEach { pos ->
            val divs = dividendsByTicker[pos.ticker.uppercase()] ?: emptyList()
            val defaultRate = getDividendTaxRate(pos.ticker)
            for (div in divs) {
                if (div.date > date) break
                val posSellDate = pos.sellDate
                if (pos.buyDate <= div.date && (posSellDate == null || posSellDate > div.date)) {
                    val rate = taxOverrides["${pos.ticker.uppercase()}::${div.date}"] ?: defaultRate
                    divPnl += convertAt(pos.quantity * div.amount * (1 - rate), div.currency, displayCurrency, div.date)
                }
            }
        }

        PortfolioChartPoint(
            date = date,
            pnl = Math.round(pricePnl + divPnl).toDouble(),
            costBasis = Math.round(costBasis).toDouble(),
            currentValue = Math.round(currentValue).toDouble(),
        )
    }
}
