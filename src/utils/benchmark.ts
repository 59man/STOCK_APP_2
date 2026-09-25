import type { Position } from '../types'
import { isClosedLot } from './rowDerivation'
import { priceAt, type TickerHistory } from './chartHistory'

export type BenchmarkKey = 'none' | 'msci' | 'sp500'

/** URTH is the iShares MSCI World ETF; ^GSPC the S&P 500 index. Both are price series. */
export const BENCHMARKS: Record<Exclude<BenchmarkKey, 'none'>, { ticker: string; label: string }> = {
  msci: { ticker: 'URTH', label: 'MSCI World' },
  sp500: { ticker: '^GSPC', label: 'S&P 500' },
}

type ConvertAt = (amount: number, from: string, to: string, date: string) => number

/**
 * "What if every buy and sell had gone into the benchmark instead" — a cash-flow-matched
 * (Long–Nickels PME) return line in the display currency, directly comparable with the
 * portfolio's own price-P&L line. Each buy buys benchmark units for the same money on the
 * same day; each sell withdraws the same money. Units can go negative when a sale returns
 * more than the benchmark would have grown to — the standard PME behaviour.
 *
 * Mirrored by core/calc/Benchmark.kt against test-fixtures/benchmark/cases.json.
 */
export function benchmarkSeries(
  positions: Position[],
  dates: string[],
  bench: TickerHistory,
  benchCurrency: string,
  displayCurrency: string,
  convertAt: ConvertAt,
): number[] {
  if (bench.length === 0) return []
  interface Flow { date: string; units: number; invested: number }
  const flows: Flow[] = []
  positions.forEach((p) => {
    const buyBench = priceAt(bench, p.buyDate)
    if (buyBench) {
      const cost = p.buyPrice * p.quantity
      flows.push({
        date: p.buyDate,
        units: convertAt(cost, p.currency, benchCurrency, p.buyDate) / buyBench,
        invested: convertAt(cost, p.currency, displayCurrency, p.buyDate),
      })
    }
    if (isClosedLot(p)) {
      const sellBench = priceAt(bench, p.sellDate!)
      if (sellBench) {
        const proceeds = p.sellPrice! * p.quantity
        flows.push({
          date: p.sellDate!,
          units: -convertAt(proceeds, p.currency, benchCurrency, p.sellDate!) / sellBench,
          invested: -convertAt(proceeds, p.currency, displayCurrency, p.sellDate!),
        })
      }
    }
  })
  flows.sort((a, b) => a.date.localeCompare(b.date))

  let i = 0
  let units = 0
  let invested = 0
  return dates.map((date) => {
    while (i < flows.length && flows[i].date <= date) {
      units += flows[i].units
      invested += flows[i].invested
      i++
    }
    const price = priceAt(bench, date) ?? 0
    return convertAt(units * price, benchCurrency, displayCurrency, date) - invested
  })
}
