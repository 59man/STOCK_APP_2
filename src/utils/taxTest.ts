import type { Position } from '../types'
import { isClosedLot } from './rowDerivation'

/**
 * Czech 3-year time test (časový test): a gain on securities held more than three years is
 * exempt. Estimate only — see the TaxPanel footer. Mirrored by core/calc/TaxTest.kt against
 * test-fixtures/tax/cases.json.
 */

const DAY_MS = 86_400_000
const parse = (d: string) => Date.UTC(+d.slice(0, 4), +d.slice(5, 7) - 1, +d.slice(8, 10))
const iso = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** First date a sale is exempt: buy date + 3 years (29 Feb clamps to 28 Feb) + 1 day. */
export function timeTestDate(buyDate: string): string {
  const y = +buyDate.slice(0, 4) + 3
  const m = +buyDate.slice(5, 7) - 1
  const lastDay = new Date(Date.UTC(y, m + 1, 0)).getUTCDate()
  const d = Math.min(+buyDate.slice(8, 10), lastDay)
  return iso(Date.UTC(y, m, d) + DAY_MS)
}

export type TaxKind = 'exempt' | 'taxable' | 'pending'
export interface LotTaxStatus { kind: TaxKind; freeFrom: string; daysLeft: number }

export function lotTaxStatus(lot: Pick<Position, 'buyDate' | 'sellDate' | 'sellPrice'>, today: string): LotTaxStatus {
  const freeFrom = timeTestDate(lot.buyDate)
  if (lot.sellPrice != null && lot.sellDate) {
    return { kind: lot.sellDate >= freeFrom ? 'exempt' : 'taxable', freeFrom, daysLeft: 0 }
  }
  const daysLeft = Math.max(0, Math.round((parse(freeFrom) - parse(today)) / DAY_MS))
  return { kind: daysLeft === 0 ? 'exempt' : 'pending', freeFrom, daysLeft }
}

export interface YearSummary {
  year: number
  proceedsTaxable: number
  gainTaxable: number
  gainExempt: number
  /** Gross proceeds of taxable sales stay within the 100 000 CZK annual small-sales limit. */
  underSmallProceedsLimit: boolean
}

export const SMALL_PROCEEDS_LIMIT_CZK = 100_000

/** Realized gains per sell year, in CZK at each trade's own date. Newest year first. */
export function realizedByYear(
  positions: Position[],
  czkAt: (amount: number, currency: string, date: string) => number,
): YearSummary[] {
  const years = new Map<number, YearSummary>()
  positions.filter(isClosedLot).forEach((lot) => {
    const year = +lot.sellDate!.slice(0, 4)
    const y = years.get(year) ?? { year, proceedsTaxable: 0, gainTaxable: 0, gainExempt: 0, underSmallProceedsLimit: true }
    const proceeds = czkAt(lot.sellPrice! * lot.quantity, lot.currency, lot.sellDate!)
    const gain = proceeds - czkAt(lot.buyPrice * lot.quantity, lot.currency, lot.buyDate)
    if (lotTaxStatus(lot, lot.sellDate!).kind === 'exempt') y.gainExempt += gain
    else {
      y.gainTaxable += gain
      y.proceedsTaxable += proceeds
    }
    years.set(year, y)
  })
  return [...years.values()]
    .map((y) => ({ ...y, underSmallProceedsLimit: y.proceedsTaxable <= SMALL_PROCEEDS_LIMIT_CZK }))
    .sort((a, b) => b.year - a.year)
}
