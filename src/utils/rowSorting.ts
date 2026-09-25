import type { PortfolioRow } from '../types'
import type { Convert } from './rowDerivation'

/** The sort fields Android offers; names match `SortField` in core/model/SortOrder.kt. */
export type SortField = 'VALUE' | 'TODAY' | 'TOTAL_RETURN'

/**
 * Orders rows the way Android's `sortedForDisplay` does: money converted to the display
 * currency before comparing, today's change read from the anchored figure (already in the
 * display currency), ties broken on ticker so the order is total.
 */
export function sortRowsForDisplay(
  rows: PortfolioRow[], field: SortField, ascending: boolean, displayCurrency: string, convert: Convert,
): PortfolioRow[] {
  const key = (r: PortfolioRow): number => {
    switch (field) {
      case 'VALUE': return convert(r.currentValue, r.currency, displayCurrency)
      case 'TODAY': return r.dailyChangeDisplay
      case 'TOTAL_RETURN': return convert(r.totalReturn, r.currency, displayCurrency)
    }
  }
  const sign = ascending ? 1 : -1
  return [...rows].sort((a, b) => sign * (key(a) - key(b)) || a.ticker.localeCompare(b.ticker))
}

/**
 * Portfolio today's change: the sum of the anchored per-row figures, which are already in the
 * display currency. Mirrors `portfolioDailyChange` in core/calc/PortfolioSummary.kt.
 */
export function portfolioDailyChange(rows: PortfolioRow[], totalValueDisplay: number): { change: number; percent: number } {
  const change = rows.reduce((s, r) => s + r.dailyChangeDisplay, 0)
  const prev = totalValueDisplay - change
  return { change, percent: prev > 0 ? (change / prev) * 100 : 0 }
}
