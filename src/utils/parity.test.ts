/**
 * Web half of the web↔Android parity check. Computes every row and portfolio figure from
 * test-fixtures/parity/input.json and compares with expected.json; the Android half
 * (core/calc ParityTest.kt) asserts against the same expected.json. Regenerate with
 * `npx vitest run -u parity`, then review the diff by hand.
 */
import { describe, expect, it } from 'vitest'
import type { Position, Quote } from '../types'
import type { DividendEvent } from './dividends'
import { computePortfolioIrr, deriveRow, groupByTicker } from './rowDerivation'
import { portfolioDailyChange, sortRowsForDisplay, type SortField } from './rowSorting'
import inputJson from '../../test-fixtures/parity/input.json'

interface Input {
  today: string
  displayCurrencies: string[]
  rates: Record<string, number>
  positions: Position[]
  quotes: Record<string, Quote>
  anchors: Record<string, { price: number; lastTradedAt: number | null }>
  /** CZK per unit at local midnight, same shape as `rates`. */
  fxAnchors: Record<string, number>
  manualPrices: Record<string, { price: number; updatedAt?: string }>
  dividends: Record<string, DividendEvent[]>
  taxOverrides: Record<string, number>
  sortFields: SortField[]
}

const input = inputJson as unknown as Input

const convert = (amount: number, from: string, to: string) => {
  if (from === to || !isFinite(amount)) return amount
  return (amount * (input.rates[from] ?? 1)) / (input.rates[to] ?? 1)
}
const fxMid = (cur: string) => (cur === 'CZK' ? 1 : input.fxAnchors[cur])

function compute(displayCurrency: string) {
  const dividends = new Map(Object.entries(input.dividends))
  const rows = groupByTicker(input.positions).map((lots) => {
    const key = lots[0].ticker.toUpperCase()
    const quote = input.quotes[key]
    return deriveRow({
      lots,
      quote,
      loading: false,
      error: null,
      manual: input.manualPrices[key],
      dividends: dividends.get(key) ?? [],
      taxOverrides: input.taxOverrides,
      today: input.today,
      convert,
      anchor: quote ? input.anchors[key] : undefined,
      anchorFx: quote
        ? quote.currency === displayCurrency ? 1 : (fxMid(quote.currency) && fxMid(displayCurrency) ? fxMid(quote.currency) / fxMid(displayCurrency) : null)
        : undefined,
      displayCurrency,
    })
  })
  const cv = (a: number, c: string) => convert(a, c, displayCurrency)
  const totalValue = rows.reduce((s, r) => s + cv(r.currentValue, r.currency), 0)
  const daily = portfolioDailyChange(rows, totalValue)
  return {
    rows: [...rows].sort((a, b) => a.ticker.localeCompare(b.ticker)).map((r) => ({
      ticker: r.ticker,
      openQuantity: r.openQuantity,
      avgBuyPrice: r.avgBuyPrice,
      currentPrice: r.currentPrice,
      currentValue: r.currentValue,
      costBasis: r.costBasis,
      pnl: r.pnl,
      dividendIncome: r.dividendIncome,
      totalReturn: r.totalReturn,
      irr: r.irr,
      dailyChangeDisplay: r.dailyChangeDisplay,
      dailyChangePercent: r.dailyChangePercent,
      dailyChangeMethod: r.dailyChangeMethod,
      isClosed: r.isClosed,
    })),
    summary: {
      totalValue,
      totalDaily: daily.change,
      totalDailyPercent: daily.percent,
      totalReturn: rows.reduce((s, r) => s + cv(r.totalReturn, r.currency), 0),
      portfolioIrr: computePortfolioIrr(input.positions, rows, dividends, input.taxOverrides, displayCurrency, input.today, convert),
    },
    sorted: Object.fromEntries(
      input.sortFields.map((f) => [f, sortRowsForDisplay(rows, f, false, displayCurrency, convert).map((r) => r.ticker)]),
    ),
  }
}

const actual = Object.fromEntries(input.displayCurrencies.map((c) => [c, compute(c)]))

describe('web/android parity fixture', () => {
  it('produces only finite numbers', () => {
    const walk = (v: unknown, path: string): void => {
      if (typeof v === 'number') expect(Number.isFinite(v), path).toBe(true)
      else if (v && typeof v === 'object') Object.entries(v).forEach(([k, x]) => walk(x, `${path}.${k}`))
    }
    walk(actual, 'actual')
  })

  it('matches expected.json', async () => {
    await expect(JSON.stringify(actual, null, 2) + '\n').toMatchFileSnapshot('../../test-fixtures/parity/expected.json')
  })
})
