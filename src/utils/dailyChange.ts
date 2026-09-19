/**
 * Today's change, anchored to midnight in the user's own time zone.
 *
 * Mirrored line-for-line by core/calc/DailyChange.kt, against the same test table — the two
 * apps must not disagree about what this number means.
 */

export type AnchorMethod =
  /** Measured against the price at the user's local midnight. */
  | 'anchored'
  /** Nothing traded since local midnight, so the change is exactly zero. */
  | 'noTradeToday'
  /** No anchor could be resolved; this is the old exchange-session figure, and the UI says so. */
  | 'prevCloseFallback'

export interface DailyChangeInput {
  quantity: number
  /** Native currency. */
  currentPrice: number
  /** Native currency; null when it could not be resolved. */
  anchorPrice: number | null
  /** Native → display, now. */
  currentFx: number
  /** Native → display at local midnight; null falls back to [currentFx]. */
  anchorFx: number | null
  /** Native; only consulted when [anchorPrice] is null. */
  prevClose?: number | null
}

export interface DailyChangeResult {
  /** Display currency, including the currency's own move. */
  change: number
  changePercent: number
  /** Display currency with FX held at today's rate — the figure broker apps show. */
  priceOnlyChange: number
  method: AnchorMethod
}

const ZERO = (method: AnchorMethod): DailyChangeResult => ({
  change: 0,
  changePercent: 0,
  priceOnlyChange: 0,
  method,
})

export function dailyChange(input: DailyChangeInput): DailyChangeResult {
  const { quantity, currentPrice, anchorPrice, currentFx, prevClose } = input
  if (quantity === 0) return ZERO('anchored')

  // No anchor: report the exchange-session figure rather than a silent zero, which would be
  // indistinguishable from a flat day and would quietly under-report the portfolio total.
  if (anchorPrice === null) {
    if (prevClose === null || prevClose === undefined) return ZERO('prevCloseFallback')
    const change = quantity * (currentPrice - prevClose) * currentFx
    const base = quantity * prevClose * currentFx
    return {
      change,
      changePercent: base > 0 ? (change / base) * 100 : 0,
      priceOnlyChange: change,
      method: 'prevCloseFallback',
    }
  }

  // Holding FX flat rather than guessing is deliberate: mixing today's rate against an
  // anchor price from midnight would invent a currency move that never happened.
  const anchorFx = input.anchorFx ?? currentFx

  const nowValue = quantity * currentPrice * currentFx
  const anchorValue = quantity * anchorPrice * anchorFx
  const change = nowValue - anchorValue
  const priceOnlyChange = quantity * (currentPrice - anchorPrice) * currentFx

  return {
    change,
    changePercent: anchorValue > 0 ? (change / anchorValue) * 100 : 0,
    priceOnlyChange,
    method: currentPrice === anchorPrice && anchorFx === currentFx ? 'noTradeToday' : 'anchored',
  }
}
