import { describe, expect, it } from 'vitest'
import type { Position, Quote } from '../types'
import { computePortfolioIrr, deriveRow, type DeriveRowInput } from './rowDerivation'

const convert = (a: number, from: string, to: string) => {
  const r: Record<string, number> = { CZK: 1, USD: 20 }
  return from === to ? a : (a * r[from]) / r[to]
}
const lot = (o: Partial<Position>): Position => ({
  id: 'l', ticker: 'T', name: 'T', type: 'stock', quantity: 10, buyPrice: 100, buyDate: '2024-01-01', currency: 'USD', ...o,
})
const quote: Quote = { ticker: 'T', price: 120, change: 2, changePercent: 1.7, currency: 'USD', name: 'T', lastUpdated: '' }
const base = (lots: Position[], extra: Partial<DeriveRowInput> = {}) =>
  deriveRow({ lots, loading: false, error: null, dividends: [], taxOverrides: {}, today: '2026-09-25', convert, displayCurrency: 'CZK', ...extra })

describe('deriveRow', () => {
  it('values an open lot at the quote', () => {
    const r = base([lot({})], { quote })
    expect(r.currentValue).toBe(1200)
    expect(r.pnl).toBe(200)
    expect(r.isClosed).toBe(false)
  })

  it('treats a lot sold at price 0 as closed, not also open', () => {
    const r = base([lot({ sellPrice: 0, sellDate: '2025-01-01' })])
    expect(r.isClosed).toBe(true)
    expect(r.currentValue).toBe(0)
    expect(r.pnl).toBe(-1000)
  })

  it('keeps averages finite for a zero-quantity lot', () => {
    const r = base([lot({ quantity: 0 })], { quote })
    expect(Number.isFinite(r.avgBuyPrice)).toBe(true)
    expect(Number.isFinite(r.currentPrice)).toBe(true)
  })
})

describe('computePortfolioIrr', () => {
  it('is not blanked by a closed row whose own IRR has no solution', () => {
    const open = base([lot({ id: 'o', ticker: 'O' })], { quote: { ...quote, ticker: 'O' } })
    const lost = base([lot({ id: 'x', ticker: 'X', sellPrice: 0, sellDate: '2025-01-01' })])
    expect(lost.irr).toBeNull()
    const irr = computePortfolioIrr([...open.positions, ...lost.positions], [open, lost], new Map(), {}, 'CZK', '2026-09-25', convert)
    expect(irr).not.toBeNull()
  })
})
