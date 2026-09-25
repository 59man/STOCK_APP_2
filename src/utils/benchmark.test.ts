import { describe, expect, it } from 'vitest'
import cases from '../../test-fixtures/benchmark/cases.json'
import { benchmarkSeries } from './benchmark'
import type { Position } from '../types'
import type { TickerHistory } from './chartHistory'

const sameCurrency = (a: number) => a

describe('benchmarkSeries', () => {
  it('matches the shared hand-computed case', () => {
    const got = benchmarkSeries(
      cases.positions as Position[], cases.dates, cases.bench as TickerHistory,
      cases.benchCurrency, cases.displayCurrency, sameCurrency,
    )
    got.forEach((v, i) => expect(v).toBeCloseTo(cases.expected[i], 6))
  })

  it('equals the holding’s own price P&L when the holding is the benchmark', () => {
    const bench: TickerHistory = [['2024-01-01', 100], ['2024-03-01', 90], ['2024-09-01', 140]]
    const lot = { id: 'x', ticker: 'X', name: 'X', type: 'etf', quantity: 3, buyPrice: 100, buyDate: '2024-01-01', currency: 'CZK' } as Position
    const got = benchmarkSeries([lot], bench.map(([d]) => d), bench, 'CZK', 'CZK', sameCurrency)
    expect(got).toEqual(bench.map(([, p]) => 3 * (p - 100)))
  })

  it('is empty without a benchmark history', () => {
    expect(benchmarkSeries([], ['2024-01-01'], [], 'USD', 'CZK', sameCurrency)).toEqual([])
  })
})
