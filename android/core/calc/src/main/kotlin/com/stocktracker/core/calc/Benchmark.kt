package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PriceHistory

enum class Benchmark(val ticker: String, val label: String) {
    MSCI_WORLD("URTH", "MSCI World"),
    SP500("^GSPC", "S&P 500"),
}

/**
 * "What if every buy and sell had gone into the benchmark instead" — a cash-flow-matched
 * (Long–Nickels PME) return line in the display currency, comparable with the portfolio's own
 * price-P&L line. Units may go negative when a sale returns more than the benchmark would
 * have grown to. Mirrors `benchmarkSeries` in src/utils/benchmark.ts.
 */
fun benchmarkSeries(
    positions: List<Position>,
    dates: List<String>,
    bench: PriceHistory,
    benchCurrency: String,
    displayCurrency: String,
    convertAt: (Double, String, String, String) -> Double,
): List<Double> {
    if (bench.isEmpty()) return emptyList()
    data class Flow(val date: String, val units: Double, val invested: Double)
    val flows = mutableListOf<Flow>()
    positions.forEach { p ->
        priceAt(bench, p.buyDate)?.takeIf { it != 0.0 }?.let { buyBench ->
            val cost = p.buyPrice * p.quantity
            flows += Flow(
                p.buyDate,
                convertAt(cost, p.currency, benchCurrency, p.buyDate) / buyBench,
                convertAt(cost, p.currency, displayCurrency, p.buyDate),
            )
        }
        if (isClosedLot(p)) {
            val sellDate = p.sellDate!!
            priceAt(bench, sellDate)?.takeIf { it != 0.0 }?.let { sellBench ->
                val proceeds = p.sellPrice!! * p.quantity
                flows += Flow(
                    sellDate,
                    -convertAt(proceeds, p.currency, benchCurrency, sellDate) / sellBench,
                    -convertAt(proceeds, p.currency, displayCurrency, sellDate),
                )
            }
        }
    }
    flows.sortBy { it.date }

    var i = 0
    var units = 0.0
    var invested = 0.0
    return dates.map { date ->
        while (i < flows.size && flows[i].date <= date) {
            units += flows[i].units
            invested += flows[i].invested
            i++
        }
        val price = priceAt(bench, date) ?: 0.0
        convertAt(units * price, benchCurrency, displayCurrency, date) - invested
    }
}
