import { describe, expect, it } from 'vitest'
import { renameTickerPrices } from './useManualPrices'
import { renameTickerTaxes } from './useManualDividendTaxes'

const entry = (price: number) => ({ price, updatedAt: '2026-10-31' })

describe('renameTickerPrices', () => {
  it('moves the price to the new ticker', () => {
    expect(renameTickerPrices({ OLD: entry(5), X: entry(1) }, 'old', 'new')).toEqual({ NEW: entry(5), X: entry(1) })
  })
  it('keeps a price the new ticker already has', () => {
    expect(renameTickerPrices({ OLD: entry(5), NEW: entry(9) }, 'OLD', 'NEW')).toEqual({ NEW: entry(9) })
  })
  it('leaves a store with no such ticker alone', () => {
    const store = { X: entry(1) }
    expect(renameTickerPrices(store, 'OLD', 'NEW')).toBe(store)
  })
})

describe('renameTickerTaxes', () => {
  it('moves every date to the new ticker and only that ticker', () => {
    expect(renameTickerTaxes({ 'OLD::2024-01-01': 0.1, 'OLD::2025-01-01': 0.2, 'OLDER::2024-01-01': 0.3 }, 'old', 'new'))
      .toEqual({ 'NEW::2024-01-01': 0.1, 'NEW::2025-01-01': 0.2, 'OLDER::2024-01-01': 0.3 })
  })
  it('keeps an override the new ticker already has for that date', () => {
    expect(renameTickerTaxes({ 'OLD::2024-01-01': 0.1, 'NEW::2024-01-01': 0.5 }, 'OLD', 'NEW'))
      .toEqual({ 'NEW::2024-01-01': 0.5 })
  })
  it('leaves a store with no such ticker alone', () => {
    const store = { 'X::2024-01-01': 0.1 }
    expect(renameTickerTaxes(store, 'OLD', 'NEW')).toBe(store)
  })
})
