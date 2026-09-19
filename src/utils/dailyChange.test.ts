import { describe, it, expect } from 'vitest'
import { dailyChange } from './dailyChange'

/**
 * This table is mirrored case-for-case in core/calc's DailyChangeTest.kt. The two apps must
 * agree on what "today's change" means, and a shared table is what enforces it — if you add a
 * case here, add it there.
 */
describe('dailyChange', () => {
  it('reports exactly zero when nothing has traded since local midnight', () => {
    const r = dailyChange({ quantity: 10, currentPrice: 100, anchorPrice: 100, currentFx: 1, anchorFx: 1 })
    expect(r.change).toBe(0)
    expect(r.changePercent).toBe(0)
    expect(r.method).toBe('noTradeToday')
  })

  it('equals the price-only figure when the display currency is the native one', () => {
    const r = dailyChange({ quantity: 10, currentPrice: 110, anchorPrice: 100, currentFx: 1, anchorFx: 1 })
    expect(r.change).toBeCloseTo(100, 9)
    expect(r.priceOnlyChange).toBeCloseTo(100, 9)
    expect(r.changePercent).toBeCloseTo(10, 9)
    expect(r.method).toBe('anchored')
  })

  it('counts currency movement when the price itself did not move', () => {
    // 10 shares at $100, held in CZK. Price flat, koruna weakened 23.00 → 23.46.
    const r = dailyChange({ quantity: 10, currentPrice: 100, anchorPrice: 100, currentFx: 23.46, anchorFx: 23 })
    expect(r.change).toBeCloseTo(10 * 100 * (23.46 - 23), 9)
    expect(r.priceOnlyChange).toBe(0)
    // The instrument did not trade, which is what the method reports — the headline moved
    // only because the koruna did, and the UI still says the market is closed.
    expect(r.method).toBe('noTradeToday')
  })

  it('nets price and currency moving in opposite directions', () => {
    // Price +10 %, koruna strengthened 23.00 → 22.00.
    const r = dailyChange({ quantity: 10, currentPrice: 110, anchorPrice: 100, currentFx: 22, anchorFx: 23 })
    expect(r.change).toBeCloseTo(10 * (110 * 22 - 100 * 23), 9)
    expect(r.priceOnlyChange).toBeCloseTo(10 * (110 - 100) * 22, 9)
    expect(r.change).toBeLessThan(r.priceOnlyChange)
    expect(r.change).toBeGreaterThan(0)
  })

  it('falls back to the previous close when no anchor could be resolved', () => {
    const r = dailyChange({
      quantity: 10, currentPrice: 110, anchorPrice: null, currentFx: 1, anchorFx: null, prevClose: 100,
    })
    expect(r.change).toBeCloseTo(100, 9)
    expect(r.method).toBe('prevCloseFallback')
  })

  it('reports zero, still marked as a fallback, when there is no previous close either', () => {
    const r = dailyChange({ quantity: 10, currentPrice: 110, anchorPrice: null, currentFx: 1, anchorFx: null })
    expect(r.change).toBe(0)
    expect(r.method).toBe('prevCloseFallback')
  })

  it('does not divide by zero on a zero anchor price', () => {
    const r = dailyChange({ quantity: 10, currentPrice: 110, anchorPrice: 0, currentFx: 1, anchorFx: 1 })
    expect(Number.isFinite(r.changePercent)).toBe(true)
    expect(r.changePercent).toBe(0)
  })

  it('reports zero for a fully closed position', () => {
    const r = dailyChange({ quantity: 0, currentPrice: 110, anchorPrice: 100, currentFx: 1, anchorFx: 1 })
    expect(r.change).toBe(0)
    expect(r.priceOnlyChange).toBe(0)
    expect(r.changePercent).toBe(0)
  })

  it('falls back to the current rate when only the anchor FX is missing', () => {
    // A price anchor without an FX anchor still beats no figure at all; holding FX flat makes
    // the result the price-only number rather than silently mixing rates from two dates.
    const r = dailyChange({ quantity: 10, currentPrice: 110, anchorPrice: 100, currentFx: 23, anchorFx: null })
    expect(r.change).toBeCloseTo(r.priceOnlyChange, 9)
    expect(r.method).toBe('anchored')
  })
})
