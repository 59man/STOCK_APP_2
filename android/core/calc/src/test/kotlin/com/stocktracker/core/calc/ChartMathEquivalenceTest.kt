package com.stocktracker.core.calc

import com.stocktracker.core.model.DivTaxOverrides
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.model.PriceHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The optimized [buildPortfolioChartData] precomputes per-lot and per-dividend terms instead
 * of recomputing them at every chart date. This pins it to the original per-date algorithm
 * (kept verbatim below as [referenceBuildPortfolioChartData]) across randomized portfolios.
 */
class ChartMathEquivalenceTest {

    private val currencies = listOf("CZK", "USD", "EUR")

    private fun dailySeries(rnd: Random, start: LocalDate, days: Int, base: Double, skipWeekends: Boolean): PriceHistory {
        var p = base
        val out = mutableListOf<Pair<String, Double>>()
        for (i in 0 until days) {
            val d = start.plusDays(i.toLong())
            p *= 1 + (rnd.nextDouble() - 0.5) * 0.04
            if (skipWeekends && d.dayOfWeek.value >= 6) continue
            out += d.toString() to p
        }
        return out
    }

    @Test
    fun `optimized chart data matches reference implementation`() {
        repeat(40) { seed ->
            val rnd = Random(seed)
            val start = LocalDate.of(2021, 1, 4)
            val days = 400 + rnd.nextInt(400)
            val tickers = (0 until 1 + rnd.nextInt(6)).map { "T$it" }
            val tickerCurrency = tickers.associateWith { currencies[rnd.nextInt(currencies.size)] }

            val positions = (0 until 2 + rnd.nextInt(12)).map { i ->
                val t = tickers[rnd.nextInt(tickers.size)]
                val buy = start.plusDays(rnd.nextInt(days).toLong())
                val sold = rnd.nextInt(3) == 0
                val sell = if (sold) buy.plusDays(1 + rnd.nextInt(200).toLong()) else null
                Position(
                    id = "p$i", ticker = t, name = t, type = PositionType.STOCK,
                    quantity = 1.0 + rnd.nextInt(50), buyPrice = 50 + rnd.nextDouble() * 100,
                    buyDate = buy.toString(),
                    // lot currency sometimes differs from the history currency
                    currency = if (rnd.nextBoolean()) tickerCurrency.getValue(t) else currencies[rnd.nextInt(3)],
                    sellDate = sell?.toString(), sellPrice = if (sold) 50 + rnd.nextDouble() * 100 else null,
                )
            }
            val histories = tickers
                .filter { rnd.nextInt(5) != 0 } // some tickers have no history at all
                .associate { it to TickerChartHistory(dailySeries(rnd, start, days, 100.0, true), tickerCurrency.getValue(it)) }
            val fx = mapOf(
                "USD" to dailySeries(rnd, start.minusDays(10), days + 20, 22.0, false),
                "EUR" to dailySeries(rnd, start.minusDays(10), days + 20, 25.0, false),
            )
            val divs = tickers.associateWith { t ->
                (0 until rnd.nextInt(12)).map { start.plusDays(rnd.nextInt(days).toLong()).toString() }
                    .distinct().sorted()
                    .map { DividendEvent(date = it, amount = rnd.nextDouble() * 3, currency = tickerCurrency.getValue(t)) }
            }
            val overrides: DivTaxOverrides = divs.flatMap { (t, evs) -> evs.filter { rnd.nextInt(4) == 0 }.map { "$t::${it.date}" to 0.3 } }.toMap()
            val spot = { a: Double, from: String, to: String ->
                val r = mapOf("CZK" to 1.0, "USD" to 23.0, "EUR" to 25.0)
                a * r.getValue(from) / r.getValue(to)
            }

            for (range in listOf(ChartRange.ALL, ChartRange.ONE_YEAR, ChartRange.THREE_MONTHS)) {
                for (display in currencies) {
                    val expected = referenceBuildPortfolioChartData(positions, divs, histories, fx, range, display, overrides, spot)
                    val actual = buildPortfolioChartData(positions, divs, histories, fx, range, display, overrides, spot)
                    assertEquals("seed=$seed range=$range display=$display size", expected.size, actual.size)
                    expected.zip(actual).forEach { (e, a) ->
                        assertEquals("seed=$seed ${e.date}", e.date, a.date)
                        // Summation order changed (prefix sums), so allow a 1-unit rounding flip.
                        assertEquals("seed=$seed ${e.date} pnl", e.pnl, a.pnl, 1.0)
                        assertEquals("seed=$seed ${e.date} cost", e.costBasis, a.costBasis, 1.0)
                        assertEquals("seed=$seed ${e.date} value", e.currentValue, a.currentValue, 1.0)
                    }
                }
            }
        }
    }
}

private fun referenceBuildPortfolioChartData(
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
